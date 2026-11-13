package com.wool.ledger.automation;

import android.app.Dialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.wool.ledger.R;
import com.wool.ledger.agent.LedgerCodec;
import com.wool.ledger.data.*;
import com.wool.ledger.ui.widget.ThemePopup;
import com.wool.ledger.util.*;

import java.util.*;
import java.util.concurrent.*;

/** 支付通知设置与核对队列，沿用账本的纸卡、主题菜单和自定义弹窗。 */
public class PaymentInboxActivity extends AppCompatActivity {
    private LinearLayout body, events;
    private PaymentStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Dialog> dialogs = new ArrayList<>();
    private boolean history;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Prefs.applyTheme(this);
        store = new PaymentStore(this);
        history = saved != null && saved.getBoolean("history");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) render();
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putBoolean("history", history);
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(3), 1);
        return t;
    }

    private GradientDrawable shape(int color, float radius, boolean stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (stroke) g.setStroke(dp(1), ThemeUtil.line(this));
        return g;
    }

    private void clickable(View v, Runnable action) {
        v.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(ThemeUtil.dark(this) ? 0x18FFFFFF : 0x101A1F24),
                        v.getBackground(),
                        shape(0xFFFFFFFF, 12, false)));
        v.setFocusable(true);
        v.setOnClickListener(
                x -> {
                    Haptic.tap(x);
                    action.run();
                });
    }

    private void gap(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void label(LinearLayout parent, String s) {
        TextView t = text(s, 11, ThemeUtil.ink3(this));
        t.setLetterSpacing(.14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(4), dp(24), 0, dp(10));
        parent.addView(t, lp);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(2), dp(16), dp(2));
        box.setBackground(shape(ThemeUtil.card(this), 16, true));
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(ThemeUtil.line(this));
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private TextView row(LinearLayout parent, String title, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(text(title, 14, ThemeUtil.ink(this)), new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = text(value, 13, ThemeUtil.ink2(this));
        detail.setGravity(Gravity.END);
        detail.setMaxWidth(dp(160));
        row.addView(detail);
        if (action != null) {
            TextView arrow = text("›", 20, ThemeUtil.ink3(this));
            arrow.setPadding(dp(8), 0, 0, 0);
            row.addView(arrow);
            clickable(row, action);
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return detail;
    }

    private TextView action(String title, boolean primary, Runnable run) {
        TextView t = text(title, 14, primary ? ThemeUtil.paper(this) : ThemeUtil.ink(this));
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(shape(primary ? ThemeUtil.ink(this) : ThemeUtil.card(this), 24, !primary));
        clickable(t, run);
        return t;
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout page = column();
        page.setBackgroundColor(ThemeUtil.paper(this));
        ViewCompat.setOnApplyWindowInsetsListener(
                page,
                (v, insets) -> {
                    androidx.core.graphics.Insets bars =
                            insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return insets;
                });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(10), dp(18), dp(14));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(ThemeUtil.ink(this));
        back.setPadding(dp(13), dp(13), dp(13), dp(13));
        back.setBackground(shape(ThemeUtil.card(this), 24, true));
        back.setContentDescription("返回");
        clickable(back, this::finish);
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column();
        titles.setPadding(dp(12), 0, 0, 0);
        TextView title = text("自动记账", 23, ThemeUtil.ink(this));
        title.setTypeface(null, Typeface.BOLD);
        titles.addView(title);
        TextView sub = text("Payment Inbox", 12, ThemeUtil.ink3(this));
        sub.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        gap(titles, 5);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = column();
        body.setPadding(dp(18), 0, dp(18), dp(28));
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        ViewCompat.requestApplyInsets(page);
        boolean
                granted =
                        NotificationManagerCompat.getEnabledListenerPackages(this)
                                .contains(getPackageName()),
                enabled = Prefs.autoCapture(this);
        LinearLayout status = card(body);
        status.setPadding(dp(18), dp(18), dp(18), dp(18));
        TextView state =
                text(
                        enabled && granted ? "正在接收交易通知" : enabled ? "等待系统授权" : "自动记账未开启",
                        18,
                        ThemeUtil.ink(this));
        state.setTypeface(null, Typeface.BOLD);
        status.addView(state);
        gap(status, 8);
        status.addView(
                text(
                        "通知使用权 · "
                                + (granted ? "已授权" : "未授权")
                                + "    /    "
                                + (enabled ? "读取已开启" : "读取已关闭"),
                        12,
                        ThemeUtil.ink2(this)));
        gap(status, 12);
        status.addView(text("完整交易自动入账，缺失信息和疑似重复由你确认。通知原文仅保存在本机。", 13, ThemeUtil.ink2(this)));
        label(body, "通知与入账");
        LinearLayout settings = card(body);
        row(settings, "自动记账", enabled ? "已开启" : "已关闭", this::capture);
        divider(settings);
        row(settings, "通知使用权", granted ? "已授权" : "去授权", this::openAccess);
        divider(settings);
        final TextView[] mode = new TextView[1];
        mode[0] =
                row(
                        settings,
                        "入账方式",
                        Prefs.autoPost(this) ? "明确交易自动入账" : "全部先确认",
                        () ->
                                ThemePopup.show(
                                        this,
                                        mode[0],
                                        new String[] {"明确交易自动入账", "全部先确认"},
                                        Prefs.autoPost(this) ? 0 : 1,
                                        i -> {
                                            Prefs.setAutoPost(this, i == 0);
                                            render();
                                        }));
        divider(settings);
        row(settings, "支付应用", packageSummary(), this::packages);
        gap(body, 10);
        TextView hint = text("仅处理所选应用的新通知。退款、转账、还款先确认；无通知或隐藏金额时需手动补记。", 12, ThemeUtil.ink3(this));
        hint.setPadding(dp(4), 0, dp(4), 0);
        body.addView(hint);
        if (getSharedPreferences("capture_health", MODE_PRIVATE).getLong("last_failure", 0) > 0) {
            gap(body, 8);
            body.addView(text("曾有通知处理失败，请核对是否漏账。", 12, ThemeUtil.ink2(this)));
        }
        label(body, "交易通知");
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(shape(ThemeUtil.line(this), 26, false));
        TextView pending =
                tab(
                        "待确认",
                        !history,
                        () -> {
                            history = false;
                            render();
                        });
        tabs.addView(pending, new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(
                tab(
                        "处理记录",
                        history,
                        () -> {
                            history = true;
                            render();
                        }),
                new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(tabs);
        gap(body, 12);
        events = column();
        body.addView(events);
        LinearLayout target = events;
        final boolean showHistory = history;
        target.addView(text("正在读取…", 13, ThemeUtil.ink3(this)));
        io.execute(
                () -> {
                    List<ContentValues> rows = store.list(showHistory ? null : "pending");
                    int count = store.pendingCount();
                    runOnUiThread(
                            () -> {
                                if (isFinishing() || events != target) return;
                                pending.setText(
                                        count > 0
                                                ? getString(R.string.payment_pending_count, count)
                                                : getString(R.string.payment_pending));
                                target.removeAllViews();
                                if (rows.isEmpty()) {
                                    LinearLayout empty = card(target);
                                    empty.setPadding(dp(22), dp(30), dp(22), dp(30));
                                    TextView t =
                                            text(
                                                    showHistory ? "还没有处理记录" : "暂无待确认交易",
                                                    17,
                                                    ThemeUtil.ink(this));
                                    t.setGravity(Gravity.CENTER);
                                    empty.addView(t);
                                    gap(empty, 10);
                                    TextView desc =
                                            text(
                                                    showHistory
                                                            ? "入账、忽略和撤销的通知会保留在这里。"
                                                            : "收到可识别的交易通知后，需要核对的记录会出现在这里。",
                                                    13,
                                                    ThemeUtil.ink3(this));
                                    desc.setGravity(Gravity.CENTER);
                                    empty.addView(desc);
                                } else for (ContentValues e : rows) addEvent(target, e);
                                if (showHistory) {
                                    gap(target, 10);
                                    target.addView(
                                            text("显示最近 100 条通知记录", 11, ThemeUtil.ink3(this)));
                                }
                            });
                });
    }

    private TextView tab(String label, boolean selected, Runnable run) {
        TextView t = text(label, 14, selected ? ThemeUtil.paper(this) : ThemeUtil.ink2(this));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(44));
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        if (selected) t.setBackground(shape(ThemeUtil.ink(this), 24, false));
        clickable(t, run);
        return t;
    }

    private String packageSummary() {
        Set<String> all =
                new LinkedHashSet<>(Arrays.asList(Prefs.capturePackages(this).split(",")));
        all.remove("");
        return all.isEmpty() ? "未选择" : all.size() + " 个应用";
    }

    private String appName(String pkg) {
        if ("com.tencent.mm".equals(pkg)) return "微信";
        if ("com.eg.android.AlipayGphone".equals(pkg)) return "支付宝";
        if ("com.unionpay".equals(pkg)) return "云闪付";
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception ignored) {
            return pkg;
        }
    }

    private void capture() {
        if (Prefs.autoCapture(this)) {
            Prefs.setAutoCapture(this, false);
            render();
            return;
        }
        Modal m = new Modal("开启支付通知读取", "只读取你选中的支付应用");
        m.content.addView(
                text(
                        "系统授权可能允许读取通知内容。羊毛账本仅处理所选应用的交易通知，提取金额、商户和时间，并在本机保存最多 1000 字通知片段用于核对。\n\n"
                                + "通知原文不上传 AI。你可以随时关闭功能或撤销系统授权。",
                        14,
                        ThemeUtil.ink2(this)));
        m.buttons(
                "暂不开启",
                "同意并开启",
                () -> {
                    Prefs.setAutoCapture(this, true);
                    m.dialog.dismiss();
                    render();
                    openAccess();
                });
        m.show();
    }

    private void openAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "请在系统设置中搜索「通知使用权」", Toast.LENGTH_LONG).show();
        }
    }

    private void packages() {
        String[] names = {"微信", "支付宝", "云闪付"},
                pkgs = {"com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay"};
        Modal m = new Modal("选择支付应用", "仅处理选中应用发出的交易通知");
        boolean[] checked = new boolean[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            checked[i] = Prefs.captures(this, pkgs[i]);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(56));
            r.addView(
                    text(names[i], 15, ThemeUtil.ink(this)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            TextView mark =
                    text(
                            checked[i] ? "✓" : "",
                            15,
                            checked[i] ? ThemeUtil.paper(this) : ThemeUtil.ink(this));
            mark.setGravity(Gravity.CENTER);
            mark.setBackground(
                    shape(checked[i] ? ThemeUtil.ink(this) : ThemeUtil.card(this), 7, true));
            r.addView(mark, new LinearLayout.LayoutParams(dp(24), dp(24)));
            r.setContentDescription(names[i] + (checked[i] ? "，已选择" : "，未选择"));
            clickable(
                    r,
                    () -> {
                        checked[index] = !checked[index];
                        mark.setText(checked[index] ? "✓" : "");
                        mark.setTextColor(
                                checked[index] ? ThemeUtil.paper(this) : ThemeUtil.ink(this));
                        mark.setBackground(
                                shape(
                                        checked[index] ? ThemeUtil.ink(this) : ThemeUtil.card(this),
                                        7,
                                        true));
                        r.setContentDescription(names[index] + (checked[index] ? "，已选择" : "，未选择"));
                    });
            m.content.addView(r);
            divider(m.content);
        }
        Set<String> customPkgs =
                new LinkedHashSet<>(Arrays.asList(Prefs.capturePackages(this).split(",")));
        customPkgs.removeAll(Arrays.asList(pkgs));
        customPkgs.remove("");
        EditText custom =
                field(
                        m.content,
                        "其他支付 / 银行应用",
                        "输入应用包名，多个用逗号分隔",
                        android.text.TextUtils.join(", ", customPkgs),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        gap(m.content, 10);
        m.content.addView(
                text("微信、支付宝和云闪付使用通用规则，实际识别效果取决于通知内容；银行应用也需核对格式。", 12, ThemeUtil.ink3(this)));
        m.buttons(
                "取消",
                "保存选择",
                () -> {
                    LinkedHashSet<String> list = new LinkedHashSet<>();
                    for (int i = 0; i < 3; i++) if (checked[i]) list.add(pkgs[i]);
                    for (String p : custom.getText().toString().trim().split("[,，\\s]+")) {
                        if (p.isEmpty()) continue;
                        if (!p.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
                                || p.equals(getPackageName())) {
                            m.error("请填写有效的支付应用包名，如 com.example.bank；不能选择羊毛账本自身。");
                            custom.requestFocus();
                            return;
                        }
                        list.add(p);
                    }
                    Prefs.setCapturePackages(this, android.text.TextUtils.join(",", list));
                    m.dialog.dismiss();
                    render();
                });
        m.show();
    }

    private void addEvent(LinearLayout parent, ContentValues e) {
        long id = e.getAsLong("id");
        String state = e.getAsString("state");
        boolean posted = "posted".equals(state), pending = "pending".equals(state);
        LinearLayout box = card(parent);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.addView(
                text(
                        (posted ? "已入账" : pending ? "待确认" : "已忽略 / 已撤销")
                                + " · "
                                + appName(e.getAsString("package_name")),
                        11,
                        ThemeUtil.ink3(this)));
        gap(box, 10);
        TextView amount =
                text(
                        e.getAsDouble("amount") > 0
                                ? (e.getAsInteger("type") == Bill.INCOME ? "+" : "−")
                                        + "¥"
                                        + Money.fmt(e.getAsDouble("amount"))
                                : "金额待补全",
                        24,
                        ThemeUtil.ink(this));
        amount.setTypeface(null, Typeface.BOLD);
        box.addView(amount);
        gap(box, 8);
        String merchant = e.getAsString("merchant");
        box.addView(
                text(
                        merchant == null || merchant.isEmpty() ? "商户信息待补全" : merchant,
                        15,
                        ThemeUtil.ink(this)));
        gap(box, 6);
        box.addView(
                text(
                        new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                                        .format(new Date(e.getAsLong("posted")))
                                + " · "
                                + e.getAsString("reason"),
                        12,
                        ThemeUtil.ink3(this)));
        row(
                box,
                "通知原文",
                "查看",
                () -> {
                    Modal m = new Modal("通知原文", "仅保存在本机，用于核对交易");
                    m.content.addView(text(e.getAsString("raw"), 14, ThemeUtil.ink2(this)));
                    m.buttons(null, "知道了", m.dialog::dismiss);
                    m.show();
                });
        if (pending) {
            LinearLayout actions = new LinearLayout(this);
            actions.addView(
                    action("忽略", false, () -> run(() -> store.ignore(id))),
                    new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 2);
            lp.leftMargin = dp(10);
            actions.addView(action("核对并记账", true, () -> edit(e)), lp);
            box.addView(actions);
        } else if (posted)
            box.addView(
                    action(
                            "撤销这笔入账",
                            false,
                            () -> {
                                Modal m = new Modal("撤销通知入账", "这笔账目将从账本中移除");
                                m.content.addView(
                                        text(
                                                "保留通知去重标记，避免再次自动入账。已被修改的账目不会被删除，请到详情页处理。",
                                                14,
                                                ThemeUtil.ink2(this)));
                                m.buttons(
                                        "保留账目",
                                        "确认撤销",
                                        () -> {
                                            m.dialog.dismiss();
                                            run(() -> store.undo(id));
                                        });
                                m.show();
                            }));
        gap(parent, 12);
    }

    private EditText field(LinearLayout parent, String label, String hint, String value, int type) {
        gap(parent, 16);
        TextView caption = text(label, 12, ThemeUtil.ink2(this));
        parent.addView(caption);
        gap(parent, 8);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setTextSize(15);
        input.setTextColor(ThemeUtil.ink(this));
        input.setHintTextColor(ThemeUtil.ink3(this));
        input.setInputType(type);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setMinHeight(dp(48));
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(shape(ThemeUtil.paper(this), 12, true));
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void edit(ContentValues event) {
        Modal m = new Modal("核对后记账", "补全交易信息，确认后写入账本");
        Bill b =
                PaymentStore.bill(
                        event.getAsDouble("amount"),
                        event.getAsInteger("type"),
                        event.getAsString("category"),
                        event.getAsString("merchant"),
                        event.getAsLong("posted"));
        EditText amount =
                field(
                        m.content,
                        "实际金额",
                        "请输入大于 0 的金额",
                        b.amount > 0 ? Money.plain(b.amount) : "",
                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note = field(m.content, "商户 / 备注", "这笔钱花在了哪里", b.note, InputType.TYPE_CLASS_TEXT);
        EditText date =
                field(
                        m.content,
                        "交易日期",
                        "yyyy-MM-dd",
                        b.billDate,
                        InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        EditText time =
                field(
                        m.content,
                        "交易时间",
                        "HH:mm",
                        b.billTime,
                        InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        gap(m.content, 16);
        String[] cats = new String[Category.ALL.size()];
        final int[] selected = {0};
        for (int i = 0; i < cats.length; i++) {
            Category c = Category.ALL.get(i);
            cats[i] = (c.type == Bill.INCOME ? "收入 · " : "支出 · ") + c.name;
            if (c.code.equals(b.category)) selected[0] = i;
        }
        final TextView[] cat = new TextView[1];
        cat[0] =
                row(
                        m.content,
                        "收支与分类",
                        cats[selected[0]],
                        () ->
                                ThemePopup.show(
                                        this,
                                        cat[0],
                                        cats,
                                        selected[0],
                                        i -> {
                                            selected[0] = i;
                                            cat[0].setText(cats[i]);
                                        }));
        divider(m.content);
        gap(m.content, 10);
        m.content.addView(text("退款、转账或还款请先核对实际用途；仅资金划转可取消并忽略通知。", 12, ThemeUtil.ink3(this)));
        m.buttons(
                "取消",
                "确认记账",
                () -> {
                    try {
                        b.amount = Double.parseDouble(amount.getText().toString());
                        b.note = note.getText().toString().trim();
                        b.billDate = date.getText().toString().trim();
                        b.billTime = time.getText().toString().trim();
                        b.slot = DateUtil.slotOf(b.billTime);
                        Category c = Category.ALL.get(selected[0]);
                        b.category = c.code;
                        b.type = c.type;
                        LedgerCodec.validate(b);
                        m.busy(true);
                        io.execute(
                                () -> {
                                    try {
                                        store.confirm(event.getAsLong("id"), b);
                                        runOnUiThread(
                                                () -> {
                                                    if (!isDestroyed()) {
                                                        m.dialog.dismiss();
                                                        render();
                                                    }
                                                });
                                    } catch (Exception failure) {
                                        runOnUiThread(
                                                () -> {
                                                    if (!isDestroyed()) {
                                                        m.busy(false);
                                                        m.error(failure.getMessage());
                                                    }
                                                });
                                    }
                                });
                    } catch (Exception invalid) {
                        m.error("金额、日期或时间不正确。请填写有效金额、yyyy-MM-dd 日期和 HH:mm 时间。");
                    }
                });
        m.show();
    }

    /** 内容滚动、操作栏固定；键盘弹出时限制高度，取消不提交。 */
    private final class Modal {
        final Dialog dialog = new Dialog(PaymentInboxActivity.this);
        final LinearLayout content = column(),
                root = column(),
                footer = new LinearLayout(PaymentInboxActivity.this);
        final TextView error =
                text(
                        "",
                        12,
                        ThemeUtil.dark(PaymentInboxActivity.this)
                                ? 0xFFF3A697
                                : getColor(R.color.danger));
        TextView positive, negative, close;

        Modal(String title, String subtitle) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root.setPadding(dp(22), dp(22), dp(22), dp(16));
            root.setBackground(shape(ThemeUtil.card(PaymentInboxActivity.this), 22, true));
            LinearLayout head = new LinearLayout(PaymentInboxActivity.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView heading = text(title, 20, ThemeUtil.ink(PaymentInboxActivity.this));
            heading.setTypeface(null, Typeface.BOLD);
            head.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            close = text("×", 24, ThemeUtil.ink3(PaymentInboxActivity.this));
            close.setGravity(Gravity.CENTER);
            close.setContentDescription("关闭弹窗");
            clickable(close, dialog::dismiss);
            head.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
            root.addView(head);
            root.addView(text(subtitle, 12, ThemeUtil.ink3(PaymentInboxActivity.this)));
            gap(root, 16);
            ScrollView scroll =
                    new ScrollView(PaymentInboxActivity.this) {
                        private final android.graphics.Rect frame = new android.graphics.Rect();

                        @Override
                        protected void onMeasure(int w, int h) {
                            int visible = getResources().getDisplayMetrics().heightPixels;
                            getWindowVisibleDisplayFrame(frame);
                            if (frame.height() > 0) visible = Math.min(visible, frame.height());
                            super.onMeasure(
                                    w,
                                    MeasureSpec.makeMeasureSpec(
                                            Math.max(dp(96), visible - dp(245)),
                                            MeasureSpec.AT_MOST));
                        }
                    };
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(content);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            error.setVisibility(View.GONE);
            error.setPadding(0, dp(12), 0, 0);
            root.addView(error);
            gap(root, 16);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(footer);
            root.setFocusableInTouchMode(true);
            dialog.setContentView(root);
            dialog.setOnDismissListener(d -> dialogs.remove(dialog));
        }

        void buttons(String cancel, String confirm, Runnable run) {
            if (cancel != null) {
                negative = action(cancel, false, dialog::dismiss);
                footer.addView(negative, new LinearLayout.LayoutParams(0, -2, 1));
            }
            positive = action(confirm, true, run);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (cancel != null) lp.leftMargin = dp(10);
            footer.addView(positive, lp);
        }

        void error(String message) {
            error.setText(message == null ? "未能完成，请重试。" : message);
            error.setVisibility(View.VISIBLE);
        }

        void busy(boolean busy) {
            positive.setEnabled(!busy);
            positive.setAlpha(busy ? .5f : 1);
            if (negative != null) negative.setEnabled(!busy);
            close.setEnabled(!busy);
            dialog.setCancelable(!busy);
        }

        void show() {
            dialogs.add(dialog);
            dialog.show();
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawableResource(android.R.color.transparent);
                w.setDimAmount(.32f);
                w.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                w.setLayout(
                        Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(440)),
                        -2);
            }
        }
    }

    private void run(Runnable work) {
        io.execute(
                () -> {
                    try {
                        work.run();
                        runOnUiThread(this::render);
                    } catch (Exception e) {
                        runOnUiThread(
                                () -> {
                                    if (!isDestroyed())
                                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG)
                                                .show();
                                });
                    }
                });
    }

    @Override
    protected void onDestroy() {
        for (Dialog d : new ArrayList<>(dialogs)) d.dismiss();
        io.shutdown();
        super.onDestroy();
    }
}
