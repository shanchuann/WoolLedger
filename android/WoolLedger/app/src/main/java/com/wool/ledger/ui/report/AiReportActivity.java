package com.wool.ledger.ui.report;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wool.ledger.R;
import com.wool.ledger.ai.LlmClient;
import com.wool.ledger.ai.LlmResult;
import com.wool.ledger.data.Bill;
import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Category;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;
import com.wool.ledger.view.ReceiptView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 月度总结：把一个月打印成一张小票。
 * 生成流程：后台读库聚合 → LlmClient 写总结 → 旧票碎裂 → 重新绘制 → clipRect 逐行吐纸。
 */
public class AiReportActivity extends AppCompatActivity {

    private String kind = "month";
    private boolean dark;
    private boolean busy;
    private int token;

    private LinearLayout root;
    private LinearLayout repSeg;
    private TextView title, sub;
    private ImageView back;
    private TextView segDay, segWeek, segMonth;
    private FrameLayout paperWrap;
    private FrameLayout pdBody;
    private View pdGlass, pdSlot, pdLed;
    private ScrollView paperStage;
    private ReceiptView receipt;
    private LinearLayout printThink;
    private TextView thinkTitle, thinkStep;
    private TextView btnAgain, btnCopy;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private ReceiptView.Data currentData;
    private String currentAi;
    private String copyLabel = "本月";
    private String thinkText = "读取账本…";
    private Runnable thinkTicker;
    private ValueAnimator revealAnim;
    private ObjectAnimator vibrateAnim;
    private int thinkIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.applyTheme(this);
        String period = getIntent().getStringExtra("period");
        if ("day".equals(period) || "week".equals(period) || "month".equals(period)) kind = period;
        setContentView(R.layout.activity_ai_report);
        bind();
        dark = ThemeUtil.dark(this);
        style();
        wire();
        generate(false);
    }

    private void bind() {
        root = findViewById(R.id.report_root);
        repSeg = findViewById(R.id.rep_seg);
        title = findViewById(R.id.report_title);
        sub = findViewById(R.id.report_sub);
        back = findViewById(R.id.report_back);
        segDay = findViewById(R.id.seg_day);
        segWeek = findViewById(R.id.seg_week);
        segMonth = findViewById(R.id.seg_month);
        paperWrap = findViewById(R.id.paper_wrap);
        pdBody = findViewById(R.id.pd_body);
        pdGlass = findViewById(R.id.pd_glass);
        pdSlot = findViewById(R.id.pd_slot);
        pdLed = findViewById(R.id.pd_led);
        paperStage = findViewById(R.id.paper_stage);
        receipt = findViewById(R.id.receipt);
        printThink = findViewById(R.id.print_think);
        thinkTitle = findViewById(R.id.think_title);
        thinkStep = findViewById(R.id.think_step);
        btnAgain = findViewById(R.id.btn_again);
        btnCopy = findViewById(R.id.btn_copy);
    }

    private void wire() {
        findViewById(R.id.report_back).setOnClickListener(v -> finish());
        segDay.setOnClickListener(v -> switchKind("day"));
        segWeek.setOnClickListener(v -> switchKind("week"));
        segMonth.setOnClickListener(v -> switchKind("month"));
        btnAgain.setOnClickListener(v -> generate(true));
        btnCopy.setOnClickListener(v -> copySummary());
    }

    private void switchKind(String k) {
        if (busy || k.equals(kind)) return;
        kind = k;
        style();
        generate(true);
    }

    @Override
    protected void onDestroy() {
        token++;
        if (revealAnim != null) revealAnim.cancel();
        stopVibrate();
        if (thinkTicker != null) main.removeCallbacks(thinkTicker);
        main.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        super.onDestroy();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private int color(int res) {
        return getColor(res);
    }

    private GradientDrawable round(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(Math.max(1, (int) dp(1)), stroke);
        return g;
    }

    private GradientDrawable oval(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    /* ===================== 主题 / 分段 ===================== */

    private void style() {
        int warm = dark ? 0xFF20232A : 0xFFE9E2D4;
        int ink = color(dark ? R.color.ink_dark : R.color.ink);
        int ink3 = color(dark ? R.color.ink3_dark : R.color.ink3);
        root.setBackgroundColor(warm);
        title.setTextColor(ink);
        sub.setTextColor(ink3);
        back.setImageTintList(ColorStateList.valueOf(dark ? 0xFFF3EFE4 : 0xFF23201B));
        receipt.setSurroundColor(warm);
        paperStage.setBackgroundColor(0x00000000);

        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF5B6169, 0xFF2B3138, 0xFF181D22});
        body.setCornerRadii(new float[]{dp(16), dp(16), dp(16), dp(16),
                dp(4), dp(4), dp(4), dp(4)});
        pdBody.setBackground(body);

        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x5CFFFFFF, 0x0DFFFFFF});
        glass.setCornerRadius(dp(10));
        glass.setStroke(Math.max(1, (int) dp(1)), 0x57FFFFFF);
        pdGlass.setBackground(glass);

        GradientDrawable slot = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF04070A, 0xFF1B2026, 0xFF242A30});
        slot.setCornerRadius(dp(6));
        pdSlot.setBackground(slot);
        pdLed.setBackground(oval(0xFF6EE7A8));

        repSeg.setBackground(round(0x00000000, dark ? 0x33FFFFFF : 0x40FFFFFF, 8));
        styleSeg(segDay, "day", ink, ink3);
        styleSeg(segWeek, "week", ink, ink3);
        styleSeg(segMonth, "month", ink, ink3);

        thinkTitle.setTextColor(ink);
        thinkStep.setTextColor(ink3);

        btnAgain.setBackground(round(0x00000000, dark ? 0xFF3A4048 : 0xFFC9C2B2, 12));
        btnAgain.setTextColor(dark ? 0xFFF3EFE4 : 0xFF4A4438);
        btnCopy.setBackground(round(dark ? 0xFF8FB8D8 : 0xFF5FA8E8, 0, 12));
        btnCopy.setTextColor(dark ? 0xFF101820 : 0xFFFFFFFF);

        // 让小票页的状态栏也落在暖纸面上
        getWindow().setStatusBarColor(warm);
        getWindow().setNavigationBarColor(warm);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void styleSeg(TextView view, String use, int ink, int ink3) {
        boolean on = kind.equals(use);
        if (on) {
            view.setBackground(round(dark ? 0xFFF3EFE4 : 0xFF23201B, 0, 7));
            view.setTextColor(dark ? 0xFF15181C : 0xFFFFFCF3);
        } else {
            view.setBackgroundColor(0x00000000);
            view.setTextColor(dark ? 0xFFB7B2A5 : 0xFF6B655A);
        }
    }

    /* ===================== 生成流程 ===================== */

    private void generate(final boolean animateOut) {
        if (busy) return;
        busy = true;
        final int my = ++token;
        btnAgain.setEnabled(false);
        btnCopy.setEnabled(false);
        showThink(true);
        if (animateOut && currentData != null && receipt.getVisibility() == View.VISIBLE) {
            receipt.shatter(() -> {
                if (my != token || isFinishing()) return;
                receipt.setBlank(true);
                load(my);
            });
        } else {
            load(my);
        }
    }

    private void load(final int my) {
        final Context app = getApplicationContext();
        pool.execute(() -> {
            try {
                List<Bill> all = new BillDao(app).listAll();
                final ReceiptView.Data data = buildData(all, app);
                final String digest = buildDigest(data, all, app);
                main.post(() -> {
                    if (my != token || isFinishing()) return;
                    LlmClient.monthReport(AiReportActivity.this, digest, r -> {
                        if (my != token || isFinishing()) return;
                        currentAi = r != null && r.text != null ? r.text : "";
                        applyAi(data, currentAi);
                        finishGeneration(my, data);
                    });
                });
            } catch (Throwable t) {
                main.post(() -> {
                    if (my != token || isFinishing()) return;
                    busy = false;
                    showThink(false);
                    setButtonsEnabled(true);
                    Toast.makeText(this, "生成失败：" + (t.getMessage() == null ? "读取账本出错" : t.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private static boolean LOCAL_HINT_SHOWN = false;

    private void finishGeneration(int my, ReceiptView.Data data) {
        if (my != token || isFinishing()) return;
        // 明确区分「真的调用了大模型」还是「本地降级」
        boolean hasKey = !com.wool.ledger.data.Prefs.apiKey(getApplicationContext()).isEmpty();
        if (!hasKey && !LOCAL_HINT_SHOWN) {
            LOCAL_HINT_SHOWN = true;
            Toast.makeText(this, "当前未配置 API Key，本次总结由本地规则生成。可在「我的 → 大模型」填写接口信息后重新生成。",
                    Toast.LENGTH_LONG).show();
        }
        currentData = data;
        showThink(false);
        receipt.setBlank(false);
        receipt.setData(data);
        receipt.setReveal(0f);
        paperStage.scrollTo(0, 0);
        receipt.setTranslationY(-dp(26));
        receipt.animate().translationY(0).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
        startVibrate();
        // 打印开始：手机给一记确认震动
        com.wool.ledger.util.Haptic.confirm(findViewById(R.id.report_root));
        findViewById(R.id.report_root).performHapticFeedback(
                android.view.HapticFeedbackConstants.CONFIRM);

        if (revealAnim != null) revealAnim.cancel();
        revealAnim = ValueAnimator.ofFloat(0f, 1f);
        revealAnim.setDuration(1600);
        revealAnim.setInterpolator(new LinearInterpolator());
        revealAnim.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            receipt.setReveal(v);
            // 打印头往下走时，纸面跟着滚，保证"吐出来"的部分一直在视野里
            int head = (int) (receipt.getMeasuredHeight() * v);
            int follow = head - paperStage.getHeight() + (int) dp(56);
            if (follow > 0) paperStage.scrollTo(0, follow);
        });
        revealAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                receipt.setReveal(1f);
                stopVibrate();
                busy = false;
                setButtonsEnabled(true);
                paperStage.smoothScrollTo(0, 0);
            }
        });
        revealAnim.start();
    }

    private void setButtonsEnabled(boolean enabled) {
        btnAgain.setEnabled(enabled);
        btnCopy.setEnabled(enabled);
        btnAgain.setAlpha(enabled ? 1f : 0.5f);
        btnCopy.setAlpha(enabled ? 1f : 0.5f);
    }

    private void showThink(boolean show) {
        if (show) {
            printThink.setVisibility(View.VISIBLE);
            printThink.setAlpha(0f);
            printThink.animate().alpha(1f).setDuration(160).start();
            thinkIndex = 0;
            thinkText = "读取账本…";
            thinkStep.setText(thinkText);
            if (thinkTicker != null) main.removeCallbacks(thinkTicker);
            thinkTicker = new Runnable() {
                @Override
                public void run() {
                    String[] steps = {"读取账本…", "按分类聚合…", "对比上期…", "撰写总结…"};
                    thinkIndex = (thinkIndex + 1) % steps.length;
                    thinkStep.setText(steps[thinkIndex]);
                    main.postDelayed(this, 420);
                }
            };
            main.postDelayed(thinkTicker, 420);
        } else {
            if (thinkTicker != null) {
                main.removeCallbacks(thinkTicker);
                thinkTicker = null;
            }
            printThink.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (printThink != null) printThink.setVisibility(View.GONE);
            }).start();
        }
    }

    private void startVibrate() {
        stopVibrate();
        vibrateAnim = ObjectAnimator.ofFloat(pdBody, "translationY", 0f, dp(1.4f));
        vibrateAnim.setDuration(80);
        vibrateAnim.setRepeatCount(ValueAnimator.INFINITE);
        vibrateAnim.setRepeatMode(ValueAnimator.REVERSE);
        vibrateAnim.start();
        ObjectAnimator led = ObjectAnimator.ofFloat(pdLed, "alpha", 1f, 0.25f);
        led.setDuration(220);
        led.setRepeatCount(ValueAnimator.INFINITE);
        led.setRepeatMode(ValueAnimator.REVERSE);
        led.start();
        ledAnim = led;
    }

    private ObjectAnimator ledAnim;

    private void stopVibrate() {
        if (vibrateAnim != null) {
            vibrateAnim.cancel();
            vibrateAnim = null;
        }
        if (ledAnim != null) {
            ledAnim.cancel();
            ledAnim = null;
        }
        if (pdBody != null) pdBody.setTranslationY(0f);
        if (pdLed != null) pdLed.setAlpha(1f);
    }

    /* ===================== 数据 → 小票 ===================== */

    private ReceiptView.Data buildData(List<Bill> all, Context ctx) {
        ReceiptView.Data d = new ReceiptView.Data();
        String today = DateUtil.today();
        String ym = today.substring(0, 7);
        int year = Integer.parseInt(today.substring(0, 4));
        int month = Integer.parseInt(today.substring(5, 7));
        int dayNum = Integer.parseInt(today.substring(8, 10));
        String from;
        String to;
        String metaDate;
        int daySpan;
        if ("day".equals(kind)) {
            from = today;
            to = today;
            d.sub = "WOOL LEDGER · " + year + "." + pad2(month) + "." + pad2(dayNum);
            metaDate = today + " " + DateUtil.nowTime();
            d.totalLabel = "当日支出";
            d.footRight = "账期 当日";
            daySpan = 1;
            copyLabel = month + " 月 " + dayNum + " 日";
        } else if ("week".equals(kind)) {
            int wd = DateUtil.weekdayIndex(today);
            from = DateUtil.addDays(today, -wd);
            to = today;
            d.sub = "WOOL LEDGER · " + year + ".W" + weekOfYear(today);
            metaDate = today + " 23:59";
            d.totalLabel = "本周支出";
            d.footRight = "账期 周";
            daySpan = wd + 1;
            copyLabel = month + " 月本周";
        } else {
            from = ym + "-01";
            to = today;
            d.sub = "WOOL LEDGER · " + year + "." + pad2(month);
            metaDate = ym + "-" + pad2(DateUtil.daysInMonth(ym)) + " 23:59";
            d.totalLabel = "本月支出";
            d.footRight = "账期 " + pad2(month) + "-01 起";
            daySpan = dayNum;
            copyLabel = month + " 月";
        }
        d.cap = "";
        d.metaLeft = metaDate;
        d.budgetRatio = -1f;

        List<Bill> expenses = new ArrayList<>();
        List<Bill> incomes = new ArrayList<>();
        for (Bill b : all) {
            if (b.billDate == null || b.billDate.compareTo(from) < 0 || b.billDate.compareTo(to) > 0) continue;
            if (b.type == Bill.EXPENSE) expenses.add(b);
            else incomes.add(b);
        }

        double total = 0;
        LinkedHashMap<String, double[]> cats = new LinkedHashMap<>();
        for (Bill b : expenses) {
            total += b.amount;
            double[] agg = cats.get(b.category);
            if (agg == null) {
                agg = new double[]{0, 0};
                cats.put(b.category, agg);
            }
            agg[0] += b.amount;
            agg[1] += 1;
        }
        double income = 0;
        LinkedHashMap<String, double[]> incCats = new LinkedHashMap<>();
        for (Bill b : incomes) {
            income += b.amount;
            double[] agg = incCats.get(b.category);
            if (agg == null) {
                agg = new double[]{0, 0};
                incCats.put(b.category, agg);
            }
            agg[0] += b.amount;
            agg[1] += 1;
        }
        d.total = Money.fmt(total);

        List<Map.Entry<String, double[]>> sorted = new ArrayList<>(cats.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

        if ("day".equals(kind)) {
            int shown = 0;
            for (Bill b : expenses) {
                if (shown >= 7) break;
                ReceiptView.Item item = new ReceiptView.Item();
                String note = b.note == null ? "" : b.note.trim();
                if (note.isEmpty() || note.contains("保密")) note = Category.nameOf(b.category);
                if (note.length() > 12) note = note.substring(0, 12);
                item.name = Category.nameOf(b.category) + " · " + note;
                item.amount = "-" + Money.plain(b.amount);
                d.items.add(item);
                shown++;
            }
            for (Bill b : incomes) {
                ReceiptView.Item item = new ReceiptView.Item();
                item.name = Category.nameOf(b.category) + " · 收入";
                item.amount = "+" + Money.plain(b.amount);
                item.income = true;
                d.items.add(item);
            }
        } else {
            int shown = 0;
            for (Map.Entry<String, double[]> e : sorted) {
                if (shown >= 5) break;
                ReceiptView.Item item = new ReceiptView.Item();
                item.name = Category.nameOf(e.getKey()) + " · " + (int) e.getValue()[1] + " 笔";
                item.amount = "-" + Money.plain(e.getValue()[0]);
                d.items.add(item);
                shown++;
            }
            int incShown = 0;
            for (Map.Entry<String, double[]> e : incCats.entrySet()) {
                if (incShown >= 2) break;
                ReceiptView.Item item = new ReceiptView.Item();
                item.name = Category.nameOf(e.getKey()) + " · " + (int) e.getValue()[1] + " 笔";
                item.amount = "+" + Money.plain(e.getValue()[0]);
                item.income = true;
                d.items.add(item);
                incShown++;
            }
        }
        if (d.items.isEmpty()) {
            ReceiptView.Item item = new ReceiptView.Item();
            item.name = "本期还没有支出";
            item.amount = "0.00";
            d.items.add(item);
        }

        int pctCount = 0;
        for (Map.Entry<String, double[]> e : sorted) {
            if (pctCount >= 3) break;
            int pct = total > 0 ? (int) Math.round(e.getValue()[0] / total * 100) : 0;
            d.pcts.add(new String[]{Category.nameOf(e.getKey()), pct + "%"});
            pctCount++;
        }

        double budget = safeBudget(ctx);
        if ("month".equals(kind)) {
            int used = budget > 0 ? (int) Math.round(total / budget * 100) : 0;
            d.cap = "预算 " + Money.fmt(budget) + " · 已用 " + used + "%";
            d.budgetRatio = budget > 0 ? (float) Math.min(1.0, total / budget) : 0f;
        } else {
            d.cap = ("week".equals(kind) ? "本周 " : "今日 ") + expenses.size() + " 笔";
        }

        d.footLeft = "共 " + expenses.size() + " 笔";
        d.footMid = "日均 " + Money.fmt(total / Math.max(1, daySpan));
        d.stamp1 = "AI 已读";
        d.stamp2 = expenses.size() + " 笔";
        String digits = today.replace("-", "");
        d.codeNo = spaced(digits + String.format(Locale.CHINA, "%04d", expenses.size()));
        d.metaRight = "No." + digits.substring(2) + pad2((int) Math.min(99, expenses.size()));
        d.thanks = "谢谢惠顾 · 一句话，记一笔";
        if (income > 0) {
            d.footMid = d.footMid + " · 收 " + Money.shortFmt(income);
        }
        return d;
    }

    private String buildDigest(ReceiptView.Data d, List<Bill> all, Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(copyLabel).append("支出 ").append(d.total).append(" 元。");
        sb.append("分类：");
        int i = 0;
        for (String[] p : d.pcts) {
            if (i > 0) sb.append("、");
            sb.append(p[0]).append(" ").append(p[1]);
            i++;
        }
        sb.append("。");
        if (!d.cap.isEmpty()) sb.append(d.cap).append("。");
        sb.append(d.footLeft).append("，").append(d.footMid).append("。");
        return sb.toString();
    }

    private void applyAi(ReceiptView.Data data, String text) {
        data.aiText = "";
        data.aiList.clear();
        if (text == null) text = "";
        List<String> paras = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String stripped = line.replaceFirst("^[0-9]+[.、)．]\\s*", "");
            boolean numbered = !stripped.equals(line);
            String dashed = stripped.replaceFirst("^[·\\-]\\s*", "");
            boolean bullet = !dashed.equals(stripped);
            if (numbered || bullet) {
                if (data.aiList.size() < 5) data.aiList.add(dashed.trim());
            } else {
                paras.add(line);
            }
        }
        StringBuilder joined = new StringBuilder();
        for (String p : paras) {
            if (joined.length() > 0) joined.append(' ');
            joined.append(p);
        }
        data.aiText = joined.toString().trim();
        if (data.aiText.isEmpty() && data.aiList.isEmpty()) {
            data.aiText = "这个月的账已经读完了，先按上面的分类慢慢改。";
        }
    }

    /* ===================== 复制 ===================== */

    private void copySummary() {
        if (currentData == null) {
            Toast.makeText(this, "还没生成完", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("羊毛账本 ").append(copyLabel).append("总结：支出 ").append(currentData.total);
        if (!currentData.cap.isEmpty()) sb.append("，").append(currentData.cap);
        sb.append("。");
        if (!currentData.pcts.isEmpty()) {
            sb.append("分类：");
            for (int i = 0; i < currentData.pcts.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(currentData.pcts.get(i)[0]).append(" ").append(currentData.pcts.get(i)[1]);
            }
            sb.append("。");
        }
        sb.append("\n");
        if (currentData.aiText != null && !currentData.aiText.isEmpty()) {
            sb.append(currentData.aiText).append("\n");
        }
        for (int i = 0; i < currentData.aiList.size(); i++) {
            sb.append(i + 1).append(". ").append(currentData.aiList.get(i)).append("\n");
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("羊毛账本 · 总结", sb.toString().trim()));
        Toast.makeText(this, "总结已复制", Toast.LENGTH_SHORT).show();
    }

    /* ===================== 小工具 ===================== */

    private static double safeBudget(Context ctx) {
        try {
            return Prefs.budget(ctx);
        } catch (Exception e) {
            return 1500d;
        }
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String spaced(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    private static int weekOfYear(String date) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            Date d = f.parse(date);
            Calendar c = Calendar.getInstance();
            if (d != null) c.setTime(d);
            return c.get(Calendar.WEEK_OF_YEAR);
        } catch (Exception e) {
            return 1;
        }
    }
}
