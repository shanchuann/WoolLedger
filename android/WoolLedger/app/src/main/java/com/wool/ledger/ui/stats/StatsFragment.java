package com.wool.ledger.ui.stats;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wool.ledger.R;
import com.wool.ledger.data.Bill;
import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Category;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.ui.chat.ChatActivity;
import com.wool.ledger.ui.report.AiReportActivity;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Anim;
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;
import com.wool.ledger.view.DayStripView;
import com.wool.ledger.view.StackedBarView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统计：头图三行结论 + 浮岛口径 + 日期圆点 + 每日堆叠柱 / 分类 / 时段 / 对比 / 习惯。
 * 所有 SQLite 读取都放在单线程池里，算完再回主线程渲染。
 */
public class StatsFragment extends Fragment {

    private final java.util.concurrent.ExecutorService aiPool = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean aiAsked = false;

    /** 有 API Key 时用真实模型换掉那句固定观察。 */
    private void askAiForTip(android.content.Context ctx, String digest, android.widget.TextView target) {
        if (aiAsked || ctx == null || target == null) return;
        if (com.wool.ledger.data.Prefs.apiKey(ctx).isEmpty()) return;
        aiAsked = true;
        com.wool.ledger.ai.LlmClient.chat(ctx, "用一句话（30 字内）点评这周的花钱情况，不要客套。", digest, r -> {
            if (r != null && r.ok && r.text != null && !r.text.trim().isEmpty()) {
                target.setText(r.text.trim());
            }
        });
    }

    private static final String[] RANGE_LABELS = {"近 7 天", "近 30 天", "近 90 天", "全年"};
    private static final int[] RANGE_DAYS = {7, 30, 90, 365};
    private static final String[] CMP_LABELS = {"上月", "去年同期", "预算"};
    private static final int[] SLOT_COLORS = {0xFF8FB8D8, 0xFFE8A15C, 0xFFA98BD1, 0xFFD98C7A};

    private int range = 0;
    /** false = 支出，true = 收入 */
    private boolean incomeOnly = false;
    private int cat = 0;
    private int cmp = 0;
    private String day = null;

    private boolean dark;
    private boolean firstResume = true;
    private ExecutorService pool;
    private Handler main;
    private double lastHeroTotal;
    private double lastCardTotal;
    private int loadToken;

    private ScrollView statScroll;
    private LinearLayout root, island, cardBars, cardCats, cardSlots, cardCmp;
    private LinearLayout catRows, slotRows, cmpRows, aiObserve;
    private LinearLayout fRange, fCat, fCmp;
    private TextView pillGo;
    private View aiDot;
    private View hero;
    private TextView fRangeLabel, fRangeValue, fCatLabel, fCatValue, fCmpLabel, fCmpValue;
    private TextView heroK, tokRange, hTotal, tokCat, hCatPct, tokCmp, hDelta;
    private TextView dTotal, dBarsLabel, dCats, dCatsLabel, dSlot, dSlotsLabel, dCmp, dCmpLabel, dKey, aiGo;
    private TextView aiTitle, aiTip;
    private TextView habitStreak, habitMax, habitAvg, habitBudget;
    private TextView habitL1, habitL2, habitL3, habitL4;
    private DayStripView dayStrip;
    private StackedBarView stackedChart;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pool = Executors.newSingleThreadExecutor();
        main = new Handler(Looper.getMainLooper());
        if (getContext() != null) dark = ThemeUtil.dark(getContext());
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stats, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bind(view);
        applyTheme();
        wire();
        if (statScroll != null) {
            statScroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> squeezeHero(sy));
        }
        refresh();
    }

    private void bind(View v) {
        root = v.findViewById(R.id.stats_root);
        statScroll = v.findViewById(R.id.stat_scroll);
        island = v.findViewById(R.id.island);
        cardBars = v.findViewById(R.id.card_bars);
        cardCats = v.findViewById(R.id.card_cats);
        cardSlots = v.findViewById(R.id.card_slots);
        cardCmp = v.findViewById(R.id.card_cmp);
        catRows = v.findViewById(R.id.cat_rows);
        slotRows = v.findViewById(R.id.slot_rows);
        cmpRows = v.findViewById(R.id.cmp_rows);
        aiObserve = v.findViewById(R.id.ai_observe);
        fRange = v.findViewById(R.id.f_range);
        fCat = v.findViewById(R.id.f_cat);
        fCmp = v.findViewById(R.id.f_cmp);
        pillGo = v.findViewById(R.id.pill_go);
        aiDot = v.findViewById(R.id.ai_dot);
        hero = v.findViewById(R.id.hero);
        ImageView heroImg = v.findViewById(R.id.hero_image);
        if (heroImg != null) {
            heroImg.setImageResource(com.wool.ledger.util.ThemeUtil.dark(requireContext())
                    ? R.drawable.hero_night_dark : R.drawable.hero_night);
        }
        fRangeLabel = v.findViewById(R.id.f_range_label);
        fRangeValue = v.findViewById(R.id.f_range_value);
        fCatLabel = v.findViewById(R.id.f_cat_label);
        fCatValue = v.findViewById(R.id.f_cat_value);
        fCmpLabel = v.findViewById(R.id.f_cmp_label);
        fCmpValue = v.findViewById(R.id.f_cmp_value);
        heroK = v.findViewById(R.id.hero_k);
        tokRange = v.findViewById(R.id.tok_range);
        hTotal = v.findViewById(R.id.h_total);
        tokCat = v.findViewById(R.id.tok_cat);
        hCatPct = v.findViewById(R.id.h_catpct);
        tokCmp = v.findViewById(R.id.tok_cmp);
        hDelta = v.findViewById(R.id.h_delta);
        dTotal = v.findViewById(R.id.d_total);
        dBarsLabel = v.findViewById(R.id.d_bars_label);
        dCats = v.findViewById(R.id.d_cats);
        dCatsLabel = v.findViewById(R.id.d_cats_label);
        dSlot = v.findViewById(R.id.d_slot);
        dSlotsLabel = v.findViewById(R.id.d_slots_label);
        dCmp = v.findViewById(R.id.d_cmp);
        dCmpLabel = v.findViewById(R.id.d_cmp_label);
        dKey = v.findViewById(R.id.d_key);
        aiGo = v.findViewById(R.id.ai_go);
        aiTitle = v.findViewById(R.id.ai_title);
        aiTip = v.findViewById(R.id.ai_tip);
        habitStreak = v.findViewById(R.id.habit_streak);
        habitMax = v.findViewById(R.id.habit_max);
        habitAvg = v.findViewById(R.id.habit_avg);
        habitBudget = v.findViewById(R.id.habit_budget);
        habitL1 = v.findViewById(R.id.habit_l1);
        habitL2 = v.findViewById(R.id.habit_l2);
        habitL3 = v.findViewById(R.id.habit_l3);
        habitL4 = v.findViewById(R.id.habit_l4);
        dayStrip = v.findViewById(R.id.day_strip);
        stackedChart = v.findViewById(R.id.stacked_chart);
        marker(tokRange);
        marker(tokCat);
        marker(tokCmp);
        ensureStatusStripe();
    }

    private void ensureStatusStripe() {
        if (statusStripe != null || getActivity() == null) return;
        android.view.ViewGroup host = getActivity().findViewById(android.R.id.content);
        if (host == null) return;
        android.view.View v = new android.view.View(getContext());
        v.setBackgroundColor(com.wool.ledger.util.ThemeUtil.paper(requireContext()));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, topInsetForStats()));
        lp.gravity = android.view.Gravity.TOP;
        v.setVisibility(android.view.View.GONE);
        host.addView(v, lp);
        statusStripe = v;
    }

    private void setStatusStripeVisible(boolean visible) {
        if (statusStripe == null) return;
        statusStripe.setBackgroundColor(com.wool.ledger.util.ThemeUtil.paper(requireContext()));
        android.view.ViewGroup.LayoutParams lp = statusStripe.getLayoutParams();
        int h = Math.max(1, topInsetForStats());
        if (lp.height != h) {
            lp.height = h;
            statusStripe.setLayoutParams(lp);
        }
        statusStripe.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** 用 @drawable/bg_hl 做 token 下沿的荧光笔，不改变行高、不撑宽父容器。 */
    private void marker(TextView view) {
        if (view == null) return;
        Drawable hl = getResources().getDrawable(R.drawable.bg_hl, getContext().getTheme());
        view.setBackground(new BottomBand(hl, dp(7)));
    }

    private static class BottomBand extends Drawable {
        private final Drawable inner;
        private final float bandHeight;

        BottomBand(Drawable inner, float bandHeight) {
            this.inner = inner;
            this.bandHeight = bandHeight;
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            int h = (int) Math.min(bandHeight, b.height() * 0.32f);
            inner.setBounds(b.left, b.bottom - h, b.right, b.bottom);
            inner.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            inner.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /* ---------- 滚动：头图压缩 322dp -> 26dp，标题上移淡出，浮岛跟到顶部 ---------- */
    private boolean islandPinned;
    /** 滚过浮岛后在状态栏区域铺的纸色条（背景透明+系统栏 setStatusBarColor 在 edge-to-edge 下可能无效） */
    private android.view.View statusStripe;

    private int topInsetForStats() {
        int sbh = com.wool.ledger.WoolApp.statusBarHeight;
        if (sbh <= 0 && getView() != null) {
            androidx.core.view.WindowInsetsCompat wi =
                    androidx.core.view.ViewCompat.getRootWindowInsets(getView());
            if (wi != null) sbh = wi.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
        }
        return Math.max(0, sbh);
    }

    private void squeezeHero(int scrollY) {
        Context ctx = getContext();
        if (ctx == null || hero == null) return;
        int heroH = Anim.dp(ctx, 322);

        // 头图与浮岛都在 ScrollView 里，天然跟手；
        // 浮岛滚到顶部后停住（钉住），回滚时自然归位。
        // 注意钉在状态栏下沿而不是屏幕 y=0：贴 0 会被状态栏盖住，卡片显示不全。
        if (island != null) {
            int top = topInsetForStats();
            int islandTop = island.getTop();
            if (islandTop > 0 && top > 0) {
                island.setTranslationY(Math.max(0, scrollY - islandTop + top));
                boolean pinned = scrollY >= islandTop - top;
                if (pinned != islandPinned) {
                    islandPinned = pinned;
                    setStatusStripeVisible(pinned);
                    if (getActivity() instanceof com.wool.ledger.MainActivity) {
                        ((com.wool.ledger.MainActivity) getActivity()).setStatsScrolled(pinned);
                    }
                }
            }
        }

        // 三行标题 / 品牌 / 压暗层：随滚动淡出（不改布局）
        float k = 1f - Math.min(1f, scrollY / (float) (heroH * 0.55f));
        if (heroK != null) heroK.setAlpha(k);
        View brand = hero.findViewById(R.id.brand_title);
        View brandSub = hero.findViewById(R.id.brand_sub);
        if (brand != null) brand.setAlpha(Math.min(1f, k * 1.5f));
        if (brandSub != null) brandSub.setAlpha(Math.min(1f, k * 1.5f));
        hero.setAlpha(0.55f + 0.45f * k);

        if (getActivity() instanceof com.wool.ledger.MainActivity) {
            ((com.wool.ledger.MainActivity) getActivity()).setChromeVisible(scrollY < Anim.dp(ctx, 60));
        }
    }

    private void wire() {
        // 三个口径都改成下拉（Design 里本来就是下拉，之前是点一下循环切换，太不直观）
        fRange.setOnClickListener(v -> com.wool.ledger.ui.widget.ThemePopup.show(
                getContext(), fRange, RANGE_LABELS, range, i -> {
                    if (i == range) return;
                    range = i;
                    day = null;
                    updateFilterLabels();
                    refresh();
                }));
        fCat.setOnClickListener(v -> {
            java.util.List<Category> cats = Category.expense();
            String[] labels = new String[cats.size() + 1];
            labels[0] = "全部";
            for (int i = 0; i < cats.size(); i++) labels[i + 1] = cats.get(i).name;
            com.wool.ledger.ui.widget.ThemePopup.show(getContext(), fCat, labels, cat, i -> {
                if (i == cat) return;
                cat = i;
                updateFilterLabels();
                refresh();
            });
        });
        fCmp.setOnClickListener(v -> com.wool.ledger.ui.widget.ThemePopup.show(
                getContext(), fCmp, CMP_LABELS, cmp, i -> {
                    if (i == cmp) return;
                    cmp = i;
                    updateFilterLabels();
                    refresh();
                }));
        dayStrip.setOnDaySelected(date -> {
            day = date.equals(day) ? null : date;
            updateFilterLabels();
            refresh();
        });
        pillGo.setOnClickListener(v -> {
            Intent i = new Intent(getContext(), AiReportActivity.class);
            i.putExtra("period", "month");
            startActivity(i);
        });
        aiObserve.setOnClickListener(v ->
                startActivity(new Intent(getContext(), ChatActivity.class)));
    }

    /**
     * 头图铺到状态栏下面之后，头图容器和图片一起长高 dy，
     * 品牌标题下移 dy，这样标题的屏幕位置和其它页的标题对齐（状态栏 + 14dp）。
     * 只改本页视图，全局内边距由 MainActivity 在切 tab 时统一切。
     */
    /** 给 MainActivity 用的幂等版本（insets 重分发 / onResume 兜底时调用） */
    public void syncHeroNow() {
        syncHero();
    }

    private void syncHero() {
        android.view.View view = getView();
        if (view == null) return;
        int sbh = com.wool.ledger.WoolApp.statusBarHeight;
        if (com.wool.ledger.WoolApp.ignoreTopInset && sbh <= 0) {
            // 进程被系统回收后恢复到统计页时，静态高度可能还没量出来；直接问窗口要，
            // 避免先按 0 排一帧、几百毫秒后再跳一条状态栏。
            androidx.core.view.WindowInsetsCompat wi =
                    androidx.core.view.ViewCompat.getRootWindowInsets(view);
            if (wi != null) sbh = wi.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
        }
        int dy = com.wool.ledger.WoolApp.ignoreTopInset ? sbh : 0;
        int baseHeroH = (int) Anim.dp(requireContext(), 322);
        int want = baseHeroH + dy;
        android.view.View heroBox = view.findViewById(R.id.hero);
        if (heroBox != null) {
            android.view.ViewGroup.LayoutParams lp = heroBox.getLayoutParams();
            if (lp.height != want) {
                lp.height = want;
                heroBox.setLayoutParams(lp);
            }
        }
        // 图片必须跟着长，否则头图顶上会露出纸色＝状态栏一条白带
        android.view.View heroImg = view.findViewById(R.id.hero_image);
        if (heroImg != null) {
            android.view.ViewGroup.LayoutParams lp = heroImg.getLayoutParams();
            if (lp.height != want) {
                lp.height = want;
                heroImg.setLayoutParams(lp);
            }
        }
        android.view.View brand = view.findViewById(R.id.hero_brand);
        if (brand != null) brand.setTranslationY(dy);
        // 从别的页面回来/insets 重放时，MainActivity 会把状态栏重置成沉浸透明；
        // 如果统计页还停在滚动位置，这里按当前 scrollY 重新对齐浮岛和状态栏底色。
        if (statScroll != null) squeezeHero(statScroll.getScrollY());
    }

    @Override
    public void onResume() {
        super.onResume();
        syncHero();
        if (getView() != null) {
            // 布局完成后再刷一次配色 / 再对一次头图（状态栏高度要等 insets 量出来）
            getView().postDelayed(this::applyTheme, 60);
            getView().postDelayed(this::syncHero, 80);
        }
        if (firstResume) {
            firstResume = false;
            return;
        }
        refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        root = null;
        if (statusStripe != null) {
            android.view.ViewParent p = statusStripe.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(statusStripe);
            statusStripe = null;
        }
        statScroll = null;
        island = null;
        cardBars = cardCats = cardSlots = cardCmp = null;
        catRows = slotRows = cmpRows = aiObserve = null;
        fRange = fCat = fCmp = null;
        pillGo = null;
        aiDot = null;
        
        fRangeLabel = fRangeValue = fCatLabel = fCatValue = fCmpLabel = fCmpValue = null;
        heroK = tokRange = hTotal = tokCat = hCatPct = tokCmp = hDelta = null;
        dTotal = dBarsLabel = dCats = dCatsLabel = dSlot = dSlotsLabel = dCmp = dCmpLabel = null;
        dKey = null;
        aiGo = null;
        aiTitle = aiTip = null;
        habitStreak = habitMax = habitAvg = habitBudget = null;
        habitL1 = habitL2 = habitL3 = habitL4 = null;
        dayStrip = null;
        stackedChart = null;
    }

    @Override
    public void onDestroy() {
        if (pool != null) pool.shutdownNow();
        if (main != null) main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** MainActivity 记完一笔 / 回到统计页时调用。 */
    public void refresh() {
        final View v = getView();
        final Context ctx = getContext();
        if (v == null || ctx == null || pool == null || pool.isShutdown()) return;
        final Context app = ctx.getApplicationContext();
        final int token = ++loadToken;
        final int r = range;
        final int c = cat;
        final int m = cmp;
        final String d = day;
        pool.execute(() -> {
            Snap snap;
            try {
                List<Bill> all = new BillDao(app).listAll();
                snap = compute(all, app, r, c, m, d);
            } catch (Throwable t) {
                return;
            }
            main.post(() -> {
                if (token != loadToken || getView() == null) return;
                render(snap);
            });
        });
    }

    /** 支出 / 收入 口径切换：只影响分类、时段、每日柱三张卡 */
    private void toggleType() {
        incomeOnly = !incomeOnly;
        refresh();
    }

    private void updateFilterLabels() {
        if (fRangeValue == null) return;
        fRangeValue.setText(day == null ? RANGE_LABELS[range] : DateUtil.shortDate(day));
        fCatValue.setText(cat == 0 ? "全部" : Category.expense().get(Math.min(cat - 1, Category.expense().size() - 1)).name);
        fCmpValue.setText(CMP_LABELS[cmp]);
    }

    /* ===================== 计算（后台线程） ===================== */

    private static class Snap {
        String from;
        String to;
        double total;
        int count;
        int catCount;
        int catPct;
        String catName = "餐饮";
        final LinkedHashMap<String, Double> cats = new LinkedHashMap<>();
        final double[] slotSum = new double[4];
        final int[] slotCount = new int[4];
        final List<StackedBarView.Bucket> buckets = new ArrayList<>();
        final List<String> days7 = new ArrayList<>();
        final Map<String, Double> daySums = new HashMap<>();
        double prev;
        boolean prevEmpty;
        String cmpText = "";
        String cmpBadge = "";
        String prevLabel = "上期";
        String tip = "";
        int streak;
        double max;
        double avg;
        int budgetPct;
    }

    private static boolean inRange(String date, String from, String to) {
        return date != null && date.compareTo(from) >= 0 && date.compareTo(to) <= 0;
    }

    private static int slotIndex(String slot) {
        for (int i = 0; i < DateUtil.SLOTS.length; i++) {
            if (DateUtil.SLOTS[i].equals(slot)) return i;
        }
        return 1;
    }

    private static int dayOfMonth(String date) {
        try {
            return Integer.parseInt(date.substring(8, 10));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int monthOf(String date) {
        try {
            return Integer.parseInt(date.substring(5, 7));
        } catch (Exception e) {
            return 0;
        }
    }

    private static double safeBudget(Context ctx) {
        try {
            return Prefs.budget(ctx);
        } catch (Exception e) {
            return 1500d;
        }
    }

    private static int daysBetween(String a, String b) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            Date da = f.parse(a);
            Date db = f.parse(b);
            if (da == null || db == null) return 0;
            return (int) Math.round((db.getTime() - da.getTime()) / 86400000.0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static double sumExpense(List<Bill> all, String from, String to) {
        double sum = 0;
        for (Bill b : all) {
            if (b.type == Bill.EXPENSE && inRange(b.billDate, from, to)) sum += b.amount;
        }
        return sum;
    }

    private Snap compute(List<Bill> all, Context ctx, int r, int c, int m, String d) {
        Snap s = new Snap();
        String today = DateUtil.today();
        int days = RANGE_DAYS[r];
        String from;
        String to;
        if (d != null) {
            from = d;
            to = d;
        } else {
            from = DateUtil.addDays(today, -(days - 1));
            to = today;
        }
        s.from = from;
        s.to = to;

        List<Bill> rows = new ArrayList<>();
        for (Bill b : all) {
            boolean typeOk = incomeOnly ? b.isIncome() : b.type == Bill.EXPENSE;
            if (typeOk && inRange(b.billDate, from, to)) rows.add(b);
        }

        HashMap<String, Double> catMap = new HashMap<>();
        for (Bill b : rows) {
            s.total += b.amount;
            Double old = catMap.get(b.category);
            catMap.put(b.category, old == null ? b.amount : old + b.amount);
            int si = slotIndex(b.slot);
            s.slotSum[si] += b.amount;
            s.slotCount[si]++;
            if (b.amount > s.max) s.max = b.amount;
        }
        s.count = rows.size();

        List<Map.Entry<String, Double>> entries = new ArrayList<>(catMap.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Double> e : entries) s.cats.put(e.getKey(), e.getValue());
        s.catCount = s.cats.size();

        // 标题第二行：全部选中时取占比最高的分类；否则取指定分类
        String featuredCode;
        if (c <= 0) {
            featuredCode = s.cats.isEmpty() ? "food" : s.cats.keySet().iterator().next();
        } else {
            List<Category> exp = incomeOnly ? Category.income() : Category.expense();
            featuredCode = exp.get(Math.min(c - 1, exp.size() - 1)).code;
        }
        s.catName = Category.nameOf(featuredCode);
        Double featured = s.cats.get(featuredCode);
        s.catPct = s.total > 0 && featured != null
                ? (int) Math.round(featured / s.total * 100) : 0;

        // 日期圆点条：永远最近 7 天
        for (int i = 6; i >= 0; i--) s.days7.add(DateUtil.addDays(today, -i));
        for (Bill b : all) {
            boolean typeOk = incomeOnly ? b.isIncome() : b.type == Bill.EXPENSE;
            if (typeOk && s.days7.contains(b.billDate)) {
                Double old = s.daySums.get(b.billDate);
                s.daySums.put(b.billDate, old == null ? b.amount : old + b.amount);
            }
        }

        // 堆叠柱
        if (d != null) {
            StackedBarView.Bucket b0 = new StackedBarView.Bucket(String.valueOf(dayOfMonth(d)));
            for (Bill b : rows) b0.add(b.category, b.amount);
            s.buckets.add(b0);
        } else {
            int bucketCount = days == 7 ? 7 : days == 30 ? 10 : days == 90 ? 9 : 12;
            for (int i = 0; i < bucketCount; i++) {
                String start = DateUtil.addDays(from, i * days / bucketCount);
                String label = days == 365 ? String.valueOf(monthOf(start)) : String.valueOf(dayOfMonth(start));
                s.buckets.add(new StackedBarView.Bucket(label));
            }
            for (Bill b : rows) {
                int idx = daysBetween(from, b.billDate) * bucketCount / Math.max(1, days);
                if (idx < 0) idx = 0;
                if (idx >= bucketCount) idx = bucketCount - 1;
                s.buckets.get(idx).add(b.category, b.amount);
            }
        }

        // 对比
        if (m == 2) {
            s.prev = safeBudget(ctx);
            double budget = s.prev > 0 ? s.prev : 1;
            if (s.total <= budget) {
                int rem = (int) Math.round((budget - s.total) / budget * 100);
                s.cmpText = "还剩 " + Math.max(0, rem) + "%";
            } else {
                int over = (int) Math.round((s.total - budget) / budget * 100);
                s.cmpText = "超 " + over + "%";
            }
            s.cmpBadge = s.cmpText;
            s.prevLabel = "预算";
        } else {
            String pf;
            String pt;
            int shift = m == 1 ? 365 : days;
            if (d != null) {
                pf = DateUtil.addDays(d, -shift);
                pt = pf;
            } else {
                pf = DateUtil.addDays(from, -shift);
                pt = DateUtil.addDays(to, -shift);
            }
            s.prev = sumExpense(all, pf, pt);
            s.prevLabel = m == 1 ? "去年同期" : "上期";
            if (s.prev <= 0) {
                // 上期没有账目：不能拿 0 做分母，直接提示无对比
                s.prevEmpty = true;
                s.cmpText = "无记录";
                s.cmpBadge = "—";
            } else {
                double delta = (s.total - s.prev) / s.prev * 100;
                if (Math.abs(delta) > 300) {
                    // 上期钱很少时百分比会爆表，改成倍数说法
                    s.cmpText = delta >= 0 ? "多 3 倍以上" : "少 3 倍以上";
                    s.cmpBadge = delta >= 0 ? "> +300%" : "< -300%";
                } else {
                    s.cmpText = (delta >= 0 ? "多 " : "少 ")
                            + String.format(Locale.CHINA, "%.1f", Math.abs(delta)) + "%";
                    s.cmpBadge = (delta >= 0 ? "+" : "")
                            + String.format(Locale.CHINA, "%.1f", delta) + "%";
                }
            }
        }

        // AI 观察文案
        int nightCnt = 0;
        double nightSum = 0;
        for (Bill b : rows) {
            if ("夜".equals(b.slot)) {
                nightCnt++;
                nightSum += b.amount;
            }
        }
        if (nightCnt > 0) {
            s.tip = "夜宵 " + nightCnt + " 笔共 ¥" + Money.shortFmt(nightSum)
                    + "，" + s.catName + "是这段的大头";
        } else {
            s.tip = "这段时间没有夜宵，" + s.catName + "是唯一没变过的";
        }

        // 连续记账天数
        Set<String> dates = new HashSet<>();
        for (Bill b : all) if (b.billDate != null) dates.add(b.billDate);
        String cursor = today;
        if (!dates.contains(cursor)) cursor = DateUtil.addDays(cursor, -1);
        int streak = 0;
        while (dates.contains(cursor) && streak < 3650) {
            streak++;
            cursor = DateUtil.addDays(cursor, -1);
        }
        s.streak = streak;

        // 日均
        s.avg = d != null ? s.total : s.total / Math.max(1, days);

        // 预算节奏：本月支出 / 预算
        String month = DateUtil.thisMonth();
        double monthSpend = 0;
        for (Bill b : all) {
            if (b.type == Bill.EXPENSE && b.billDate != null && b.billDate.startsWith(month)) {
                monthSpend += b.amount;
            }
        }
        double budget = safeBudget(ctx);
        s.budgetPct = budget > 0 ? (int) Math.round(monthSpend / budget * 100) : 0;
        return s;
    }

    /* ===================== 渲染（主线程） ===================== */

    private void render(Snap s) {
        if (root == null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        dark = ThemeUtil.dark(ctx);

        String today = DateUtil.today();
        int year = Integer.parseInt(today.substring(0, 4));
        int month = Integer.parseInt(today.substring(5, 7));
        heroK.setText(year + " · " + month + " 月账单");
        tokRange.setText(day == null ? RANGE_LABELS[range] : DateUtil.shortDate(day));
        animateMoney(hTotal, lastHeroTotal, s.total, 620);
        lastHeroTotal = s.total;
        tokCat.setText(s.catName);
        hCatPct.setText(s.catPct + "%");
        tokCmp.setText(CMP_LABELS[cmp]);
        hDelta.setText(s.cmpText);

        updateFilterLabels();
        if (dBarsLabel != null) {
            dBarsLabel.setText(incomeOnly ? "每日收入" : "每日支出");
            dBarsLabel.setOnClickListener(v2 -> toggleType());
        }

        animateMoney(dTotal, lastCardTotal, s.total, 620);
        lastCardTotal = s.total;
        dCats.setText(s.catCount + " 类");
        dSlot.setText("¥" + Money.fmt(s.total));
        dCmp.setText(s.cmpBadge);

        android.view.View emptyView = getView() == null ? null : getView().findViewById(R.id.stat_empty);
        boolean empty = s.count == 0;
        if (emptyView != null) emptyView.setVisibility(empty ? android.view.View.VISIBLE : android.view.View.GONE);
        if (stackedChart != null) stackedChart.setVisibility(empty ? android.view.View.GONE : android.view.View.VISIBLE);
        if (cardCats != null) cardCats.setVisibility(empty ? android.view.View.GONE : android.view.View.VISIBLE);
        if (cardSlots != null) cardSlots.setVisibility(empty ? android.view.View.GONE : android.view.View.VISIBLE);
        if (cardCmp != null) cardCmp.setVisibility(empty ? android.view.View.GONE : android.view.View.VISIBLE);

        if (dKey != null) {
            dKey.setText(s.count == 0
                    ? "该时间段暂无记录，可切换范围或新增一笔。"
                    : "按分类堆叠，颜色与下方分类一致");
        }
        dayStrip.setDays(s.days7, s.daySums, day, today);
        stackedChart.setData(s.buckets, true);
        renderCats(s);
        if (stackedChart != null) {
            stackedChart.setOnDayClick(date -> {
                android.content.Intent it = new android.content.Intent(getContext(), com.wool.ledger.BillListActivity.class);
                it.putExtra("keyword", "");
                it.putExtra("category", (String) null);
                startActivity(it);
            });
        }
        renderSlots(s);
        renderCompare(s);

        aiTitle.setText(day != null ? "蓝羊羊 · 这一天的观察"
                : range == 0 ? "蓝羊羊 · 这一周的观察" : "蓝羊羊 · 这一段的观察");
        aiTip.setText(s.tip);
        habitStreak.setText(s.streak + " 天");
        habitMax.setText("¥" + Money.shortFmt(s.max));
        habitAvg.setText("¥" + Money.shortFmt(s.avg));
        habitBudget.setText(s.budgetPct + "%");
    }

    private int color(int res) {
        Context c = getContext();
        return c == null ? 0xFF1A1F24 : c.getColor(res);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void renderCats(Snap s) {
        if (catRows == null) return;
        catRows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        int ink = color(dark ? R.color.ink_dark : R.color.ink);
        int ink3 = color(dark ? R.color.ink3_dark : R.color.ink3);
        if (s.cats.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("这段时间还没有支出");
            empty.setTextSize(11);
            empty.setTextColor(ink3);
            empty.setPadding(0, (int) dp(8), 0, (int) dp(8));
            catRows.addView(empty);
            return;
        }
        for (Map.Entry<String, Double> e : s.cats.entrySet()) {
            View row = inflater.inflate(R.layout.item_cat_row, catRows, false);
            Category category = Category.byCode(e.getKey());
            View dot = row.findViewById(R.id.cat_dot);
            dot.setBackground(round(category.color, 0, 2.5f));
            TextView name = row.findViewById(R.id.cat_name);
            name.setText(category.name);
            name.setTextColor(ink);
            FrameLayout track = row.findViewById(R.id.cat_track);
            View trackBg = row.findViewById(R.id.cat_track_bg);
            trackBg.setBackground(round(dark ? 0x14FFFFFF : 0x0E1A1F24, 0, 4.5f));
            View bar = row.findViewById(R.id.cat_bar);
            bar.setBackground(round(category.color, 0, 4.5f));
            TextView val = row.findViewById(R.id.cat_val);
            val.setText(Money.shortFmt(e.getValue()));
            val.setTextColor(ink);
            TextView pct = row.findViewById(R.id.cat_pct);
            pct.setText(Math.round(e.getValue() / Math.max(0.0001, s.total) * 100) + "%");
            pct.setTextColor(ink3);
            float frac = s.total > 0 ? (float) (e.getValue() / s.total) : 0f;
            animateBar(bar, track, frac);
            // 点分类行下钻到账单页，并按该分类过滤
            final String catName = category.name;
            row.setOnClickListener(v -> {
                android.content.Intent it = new android.content.Intent(getContext(), com.wool.ledger.BillListActivity.class);
                it.putExtra("category", catName);
                startActivity(it);
            });
            catRows.addView(row);
        }
    }

    private void renderSlots(Snap s) {
        if (slotRows == null) return;
        slotRows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        int ink = color(dark ? R.color.ink_dark : R.color.ink);
        int ink2 = color(dark ? R.color.ink2_dark : R.color.ink2);
        double max = 0;
        for (double v : s.slotSum) max = Math.max(max, v);
        if (max <= 0) max = 1;
        for (int i = 0; i < 4; i++) {
            View row = inflater.inflate(R.layout.item_slot_row, slotRows, false);
            TextView name = row.findViewById(R.id.slot_name);
            name.setText(DateUtil.SLOTS[i]);
            name.setTextColor(ink);
            FrameLayout track = row.findViewById(R.id.slot_track);
            View trackBg = row.findViewById(R.id.slot_track_bg);
            trackBg.setBackground(round(dark ? 0x14FFFFFF : 0x0E1A1F24, 0, 5f));
            View bar = row.findViewById(R.id.slot_bar);
            bar.setBackground(round(SLOT_COLORS[i], 0, 5f));
            TextView val = row.findViewById(R.id.slot_val);
            val.setTextColor(ink2);
            if (s.slotCount[i] > 0) {
                val.setText("¥" + Money.fmt(s.slotSum[i]));
                animateBar(bar, track, (float) (s.slotSum[i] / max));
            } else {
                val.setText("—");
                animateBar(bar, track, 0f);
            }
            slotRows.addView(row);
        }
    }

    private void renderCompare(Snap s) {
        if (cmpRows == null) return;
        cmpRows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        int ink = color(dark ? R.color.ink_dark : R.color.ink);
        int gray = dark ? 0x59FFFFFF : 0x591A1F24;
        double max = Math.max(s.total, s.prev);
        if (max <= 0) max = 1;

        View now = inflater.inflate(R.layout.item_cmp_row, cmpRows, false);
        TextView nowName = now.findViewById(R.id.cmp_name);
        nowName.setText("本期");
        nowName.setTextColor(color(dark ? R.color.ink2_dark : R.color.ink2));
        FrameLayout nowTrack = now.findViewById(R.id.cmp_track);
        ((View) nowTrack.getChildAt(0)).setBackground(
                round(dark ? 0x14FFFFFF : 0x0E1A1F24, 0, 5f));
        View nowBar = now.findViewById(R.id.cmp_bar);
        nowBar.setBackground(round(ink, 0, 5f));
        TextView nowVal = now.findViewById(R.id.cmp_val);
        nowVal.setText("¥" + Money.fmt(s.total));
        nowVal.setTextColor(ink);
        animateBar(nowBar, nowTrack, (float) (s.total / max));
        cmpRows.addView(now);

        if (s.prevEmpty) {
            TextView empty = new TextView(getContext());
            empty.setText("上期无数据，暂不能计算涨跌");
            empty.setTextSize(11);
            empty.setTextColor(color(dark ? R.color.ink3_dark : R.color.ink3));
            empty.setPadding(0, (int) dp(8), 0, (int) dp(2));
            cmpRows.addView(empty);
            return;
        }

        View prev = inflater.inflate(R.layout.item_cmp_row, cmpRows, false);
        TextView prevName = prev.findViewById(R.id.cmp_name);
        prevName.setText(s.prevLabel);
        prevName.setTextColor(color(dark ? R.color.ink2_dark : R.color.ink2));
        FrameLayout prevTrack = prev.findViewById(R.id.cmp_track);
        ((View) prevTrack.getChildAt(0)).setBackground(
                round(dark ? 0x14FFFFFF : 0x0E1A1F24, 0, 5f));
        View prevBar = prev.findViewById(R.id.cmp_bar);
        prevBar.setBackground(round(gray, 0, 5f));
        TextView prevVal = prev.findViewById(R.id.cmp_val);
        prevVal.setText("¥" + Money.fmt(s.prev));
        prevVal.setTextColor(ink);
        animateBar(prevBar, prevTrack, (float) (s.prev / max));
        cmpRows.addView(prev);
    }

    private void animateMoney(final TextView tv, double from, double to, int dur) {
        if (tv == null) return;
        ValueAnimator a = ValueAnimator.ofFloat((float) from, (float) to);
        a.setDuration(dur);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(anim -> {
            double v = (Float) anim.getAnimatedValue();
            tv.setText("¥" + Money.fmt(v));
        });
        a.start();
    }

    private void animateBar(final View bar, final View track, final float frac) {
        if (bar == null || track == null) return;
        final float f = Math.max(0f, Math.min(1f, frac));
        track.post(() -> {
            int measured = track.getWidth();
            final int full = measured <= 0 ? 1 : measured;
            ValueAnimator a = ValueAnimator.ofFloat(0f, f);
            a.setDuration(520);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(anim -> {
                float v = (Float) anim.getAnimatedValue();
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                lp.width = (int) (full * v);
                bar.setLayoutParams(lp);
            });
            a.start();
        });
    }

    /* ===================== 主题 ===================== */

    private void applyTheme() {
        if (root == null) return;
        Context c = getContext();
        if (c == null) return;
        dark = ThemeUtil.dark(c);
        int paper = c.getColor(dark ? R.color.paper_dark : R.color.paper);
        int card = c.getColor(dark ? R.color.card_dark : R.color.paper_card);
        int line = c.getColor(dark ? R.color.line_dark : R.color.line);
        int ink = c.getColor(dark ? R.color.ink_dark : R.color.ink);
        int ink3 = c.getColor(dark ? R.color.ink3_dark : R.color.ink3);
        int blueDeep = c.getColor(dark ? R.color.sky : R.color.blue_deep);

        root.setBackgroundColor(paper);
        // 头图容器底色跟随主题（否则深色下图片自带的白色渐变会透出来）
        if (hero != null) hero.setBackgroundColor(paper);
        applyHeroFade(dark, paper);
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB30B1016, 0x660B1016, 0x000B1016});
        GradientDrawable cardBg = round(card, line, 14);
        cardBars.setBackground(cardBg);
        cardCats.setBackground(round(card, line, 14));
        cardSlots.setBackground(round(card, line, 14));
        cardCmp.setBackground(round(card, line, 14));

        dBarsLabel.setTextColor(ink3);
        dCatsLabel.setTextColor(ink3);
        dSlotsLabel.setTextColor(ink3);
        dCmpLabel.setTextColor(ink3);
        dTotal.setTextColor(ink);
        dCats.setTextColor(ink);
        dSlot.setTextColor(ink);
        dCmp.setTextColor(ink);
        aiTitle.setTextColor(ink);
        aiTip.setTextColor(ink3);
        dKey.setTextColor(ink3);
        aiGo.setTextColor(blueDeep);
        habitL1.setTextColor(ink3);
        habitL2.setTextColor(ink3);
        habitL3.setTextColor(ink3);
        habitL4.setTextColor(ink3);
        habitStreak.setTextColor(ink);
        habitMax.setTextColor(ink);
        habitAvg.setTextColor(ink);
        habitBudget.setTextColor(ink);
        aiObserve.setBackground(round(card, line, 14));

        if (dark) {
            island.setBackground(round(card, line, 20));
        } else {
            island.setBackgroundResource(R.drawable.bg_pill_white);
        }
        GradientDrawable capsule = round(dark ? 0x14FFFFFF : 0x0E1A1F24, 0, 13);
        fRange.setBackground(capsule);
        fCat.setBackground(round(dark ? 0x14FFFFFF : 0x0E1A1F24, 0, 13));
        fCmp.setBackground(round(dark ? 0x14FFFFFF : 0x0E1A1F24, 0, 13));
        fRangeLabel.setTextColor(ink3);
        fCatLabel.setTextColor(ink3);
        fCmpLabel.setTextColor(ink3);
        fRangeValue.setTextColor(ink);
        fCatValue.setTextColor(ink);
        fCmpValue.setTextColor(ink);

        aiDot.setBackground(round(c.getColor(dark ? R.color.sky : R.color.blue2), 0, 3));
        GradientDrawable go = new GradientDrawable();
        go.setShape(GradientDrawable.OVAL);
        go.setColor(dark ? 0xFFF3EFE4 : 0xFF12171E);
        pillGo.setBackground(go);
        pillGo.setTextColor(dark ? 0xFF15181C : 0xFFFFFFFF);

        stackedChart.setDark(dark);
        dayStrip.setDark(dark);
    }


    private GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, (int) dp(1)), stroke);
        return g;
    }

    /** 深色：头图自带白色渐变，盖一层透明→纸色压掉 */
    private void applyHeroFade(boolean dark, int paper) {
        if (hero == null) return;
        android.view.ViewGroup heroGroup = (android.view.ViewGroup) hero;
        android.view.View old = heroGroup.findViewWithTag("hero_fade");
        if (old != null) heroGroup.removeView(old);
        if (!dark) return;
        android.view.View fade = new android.view.View(getContext());
        fade.setTag("hero_fade");
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x0012171E, 0xB312171E, 0xFF12171E}));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) Anim.dp(getContext(), 190));
        lp.gravity = android.view.Gravity.BOTTOM;
        // 插到图片之上、文字之下（index 1），否则会把标题压暗
        heroGroup.addView(fade, 1, lp);
    }
}
