package com.wool.ledger;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.util.Haptic;
import com.wool.ledger.data.Seed;
import com.wool.ledger.ui.add.AddRecordActivity;
import com.wool.ledger.ui.bills.BillListFragment;
import com.wool.ledger.ui.chat.ChatActivity;
import com.wool.ledger.ui.home.HomeFragment;
import com.wool.ledger.ui.settings.SettingsFragment;
import com.wool.ledger.ui.stats.StatsFragment;

/** 单 Activity + 四个主页面。记一笔是独立 Activity（Intent 传值 + 回传刷新）。 */
public class MainActivity extends AppCompatActivity {

    public static final int REQ_ADD = 1001;

    private LinearLayout tabBills, tabStats, tabAdd, tabMe;
    private String current = "bills";

    private static final String KEY_CURRENT = "current_tab";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.applyTheme(this);
        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(KEY_CURRENT);
            if (saved != null) current = saved;
        }
        setContentView(R.layout.activity_main);

        Seed.maybeSeed(this);
        String rem = Prefs.reminder(this);
        if (!"关闭".equals(rem)) com.wool.ledger.notify.Reminder.schedule(this, rem);
        new Thread(() -> {
            try {
                com.wool.ledger.data.BillDao dao = new com.wool.ledger.data.BillDao(this);
                dao.fixTypes();
            } catch (Throwable ignored) {
            }
        }).start();

        tabBills = findViewById(R.id.tab_bills);
        tabStats = findViewById(R.id.tab_stats);
        tabAdd = findViewById(R.id.tab_add);
        tabMe = findViewById(R.id.tab_me);

        tabBills.setOnClickListener(v -> select("bills"));
        tabStats.setOnClickListener(v -> select("stats"));
        tabMe.setOnClickListener(v -> select("me"));
        tabAdd.setOnClickListener(v -> openAdd(0, null, null));

        findViewById(R.id.nav_ai).setOnClickListener(v ->
                startActivity(new Intent(this, ChatActivity.class)));

        ImageView theme = findViewById(R.id.theme_toggle);
        theme.setOnClickListener(v -> toggleTheme(v));
        theme.setImageResource(Prefs.dark(this) ? R.drawable.ic_sun : R.drawable.ic_moon);

        // 先按主题把状态栏/窗口底色铺好，再选 tab（统计页会在 select 里切成沉浸，别被这里的默认色覆盖）
        applyWindowTheme();
        // insets 每次重新分发（切主题重建、旋转等）都回来对一次，防止"昼夜切换后立刻切 tab"的竞态
        com.wool.ledger.WoolApp.onInsetsApplied = insetsCallback;

        // 首次进入默认账单页；重建（如切主题）时回到原来的 tab
        select(current == null ? "bills" : current);

        // 切主题重建：先把旧界面截图铺成窗口背景，涟漪圆外面保持旧主题，
        // 圆里面才是刚重建出来的新主题；状态栏/导航栏也先按旧主题铺，动画结束再换新主题。
        if (com.wool.ledger.util.ThemeReveal.pending) {
            android.graphics.Bitmap snap = com.wool.ledger.util.ThemeReveal.snapshot;
            revealRunning = true;
            if (snap != null && !snap.isRecycled()) {
                getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.BitmapDrawable(getResources(), snap));
            }
            // 状态栏在点按那一刻就已经切到新主题了（见 toggleTheme），这里不再冻结成旧主题
        }

        playThemeReveal();
        syncNavTheme(Prefs.dark(this));
        getWindow().getDecorView().postDelayed(() ->
                com.wool.ledger.util.ThemeUtil.repaint(this,
                        findViewById(android.R.id.content)), 120);
    }

    /** 主题切换后，从昼夜钮位置圆形扩散出新界面 */
    private void playThemeReveal() {
        if (!com.wool.ledger.util.ThemeReveal.pending) return;
        final android.view.View root = findViewById(android.R.id.content);
        if (root == null) {
            com.wool.ledger.util.ThemeReveal.clearPending();
            finishThemeReveal();
            return;
        }
        final int cx = com.wool.ledger.util.ThemeReveal.cx;
        final int cy = com.wool.ledger.util.ThemeReveal.cy;
        com.wool.ledger.util.ThemeReveal.clearPending();
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                if (isFinishing() || root.getWidth() == 0) {
                    finishThemeReveal();
                    return true;
                }
                int w = root.getWidth();
                int h = root.getHeight();
                int[] loc = new int[2];
                root.getLocationOnScreen(loc);
                float x = Math.max(0, Math.min(w, cx - loc[0]));
                float y = Math.max(0, Math.min(h, cy - loc[1]));
                float max = (float) Math.hypot(Math.max(x, w - x), Math.max(y, h - y));
                try {
                    android.animation.Animator a = android.view.ViewAnimationUtils
                            .createCircularReveal(root, (int) x, (int) y, 0f, max);
                    a.setDuration(460);
                    a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
                    a.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            finishThemeReveal();
                        }

                        @Override
                        public void onAnimationCancel(android.animation.Animator animation) {
                            finishThemeReveal();
                        }
                    });
                    a.start();
                } catch (Throwable t) {
                    // 部分设备关掉硬件加速时圆形 reveal 可能起不来：直接收尾，别让状态栏/旧截图卡住
                    finishThemeReveal();
                    return true;
                }
                // 兜底：动画回调万一没来（被系统打断等），1 秒后强制收尾
                root.postDelayed(() -> finishThemeReveal(), 1000);
                // 这一帧先不画，交给扩散动画从按钮处展开
                return false;
            }
        });
    }

    /** 切换前的状态栏 / 导航栏配色：涟漪还在扩散时先维持旧主题 */
    /** 一次性把系统栏配色/图标明暗写成目标主题（新旧两套都走这里，避免半新半旧） */
    private void applySystemBars(boolean dark, boolean statsFullBleed) {
        int paper = getColor(dark ? R.color.paper_dark : R.color.paper);
        getWindow().setStatusBarColor(statsFullBleed ? 0x00000000 : paper);
        getWindow().setNavigationBarColor(paper);
        try {
            androidx.core.view.WindowInsetsControllerCompat ctrl =
                    androidx.core.view.WindowCompat.getInsetsController(
                            getWindow(), getWindow().getDecorView());
            // 统计页头图铺到状态栏下，图标始终用白色；其它页跟随主题
            ctrl.setAppearanceLightStatusBars(!dark && !statsFullBleed);
            ctrl.setAppearanceLightNavigationBars(!dark);
        } catch (Throwable ignored) {
        }
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (dark || statsFullBleed) flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (dark) flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /** 涟漪扩散完：撤掉旧截图，窗口底色 / 状态栏 / 导航栏换成新主题 */
    private void finishThemeReveal() {
        revealRunning = false;
        // 旧实例被销毁时动画可能被 cancel，这时不要把新实例还要用的截图回收掉
        if (isFinishing() || isDestroyed()) return;
        try {
            int paper = getColor(Prefs.dark(this) ? R.color.paper_dark : R.color.paper);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(paper));
            applyWindowTheme();
            setStatsFullBleed("stats".equals(current));
        } catch (Throwable ignored) {
        }
        com.wool.ledger.util.ThemeReveal.releaseSnapshot();
    }

    private void select(String key) {
        select(key, false);
    }

    private void select(String key, boolean force) {
        Haptic.tap(findViewById(R.id.nav_bar));
        if (!force && key.equals(current) && getSupportFragmentManager().findFragmentById(R.id.container) != null) {
            // 重建（切主题 recreate）恢复到统计页时也要先把内边距对好，避免首帧多让一条状态栏
            setStatsFullBleed("stats".equals(key));
            return;
        }
        String previous = current;
        current = key;
        boolean statsEdge = "stats".equals(key) || "stats".equals(previous);
        // 进/出统计页会改窗口内边距，旧页面不能按新内边距重排后再淡出（标题会跳）。
        // 先截一张旧画面，同步换页后用它盖着淡出，观感是两张页面交叉淡化，而不是硬切。
        android.graphics.Bitmap oldSnap = statsEdge ? captureWindowSnapshot() : null;
        // 统计页要沉浸、其它三页要正常内边距；统一在这里切，别依赖 fragment 生命周期
        // （replace + 动画的事务时序不可靠，之前切页会把内容顶上状态栏、状态栏也变白）
        setStatsFullBleed("stats".equals(key));
        Fragment f;
        if ("stats".equals(key)) f = new StatsFragment();
        else if ("me".equals(key)) f = new SettingsFragment();
        else f = new HomeFragment();

        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        if (!statsEdge) {
            // 进/出统计页会同时改窗口内边距：必须同步执行事务，否则旧页面会先按新内边距
            // 被画一帧（标题先跳上去）再淡出，看起来就是“标题上移 + 缓入缓出”。
            tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
        }
        tx.replace(R.id.container, f);
        if (statsEdge) {
            tx.commitNowAllowingStateLoss();
            playTabCrossfade(oldSnap);
        } else {
            tx.commitAllowingStateLoss();
        }
        syncNav();
    }

    private View navIndicator;
    /** 切主题涟漪进行中：这期间不许 applyWindowTheme 把窗口背景/系统栏改回新主题 */
    private boolean revealRunning;
    /** tab 切换的旧页面截图淡出层（统计页进出不再硬切） */
    private android.widget.ImageView tabFadeOverlay;
    private android.graphics.Bitmap tabFadeBitmap;

    private void slideIndicator(boolean animate) {
        if (navIndicator != null && Prefs.dark(this)) {
            navIndicator.setBackgroundResource(R.drawable.bg_nav_item_on_dark);
        } else if (navIndicator != null) {
            navIndicator.setBackgroundResource(R.drawable.bg_nav_item_on);
        }
        if (navIndicator == null) {
            navIndicator = findViewById(R.id.nav_indicator);
        }
        if (navIndicator == null) return;
        LinearLayout active = "stats".equals(current) ? tabStats : "me".equals(current) ? tabMe : tabBills;
        if (active == null) return;
        android.view.View row = (android.view.View) active.getParent();
        int left = row.getLeft() + active.getLeft() - navIndicator.getLeft();
        int w = active.getWidth();
        if (w == 0) {
            navIndicator.post(() -> slideIndicator(animate));
            return;
        }
        android.view.ViewGroup.LayoutParams lp = navIndicator.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            navIndicator.setLayoutParams(lp);
        }
        float target = left;
        if (animate) {
            navIndicator.animate().translationX(target).setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        } else {
            navIndicator.setTranslationX(target);
        }
    }

    private void syncNav() {
        syncNav(true);
    }

    private void syncNav(boolean animate) {
        boolean darkNow = Prefs.dark(this);
        int on = darkNow ? 0xFFF3EFE4 : com.wool.ledger.util.ThemeUtil.ink(this);
        int off = darkNow ? 0xFF9EA9B5 : com.wool.ledger.util.ThemeUtil.ink2(this);
        style(tabBills, "bills".equals(current), on, off);
        style(tabStats, "stats".equals(current), on, off);
        style(tabMe, "me".equals(current), on, off);
        style(tabAdd, false, on, off);
        slideIndicator(animate);
        if (Prefs.dark(this)) {
            final android.view.View box = findViewById(R.id.container);
            if (box != null) box.post(() -> com.wool.ledger.util.ThemeUtil.repaint(this, box));
        }
    }

    private void style(LinearLayout item, boolean active, int on, int off) {
        item.setBackground(null);
        TextView t = (TextView) item.getChildAt(1);
        ImageView i = (ImageView) item.getChildAt(0);
        int c = active ? on : off;
        t.setTextColor(c);
        i.setColorFilter(c);
        t.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    public void openAdd(long billId, String date, String slot) {
        Intent i = new Intent(this, AddRecordActivity.class);
        i.putExtra("bill_id", billId);
        if (date != null) i.putExtra("date", date);
        if (slot != null) i.putExtra("slot", slot);
        // 让记一笔从导航按钮位置圆形展开（旧参数保留，兼容聊天/详情里的“改一下”）
        int[] loc = new int[2];
        tabAdd.getLocationInWindow(loc);
        i.putExtra("reveal_x", loc[0] + tabAdd.getWidth() / 2);
        i.putExtra("reveal_y", loc[1] + tabAdd.getHeight() / 2);
        // 不做共享元素“瞬移”：整页用 Activity Fade 交叉淡化，导航栏→保存按钮、
        // 蓝羊羊→语音按钮跟着界面一起淡入淡出（位置尺寸已在布局里对齐）。
        startActivityForResult(i, REQ_ADD);
        // 显式指定 fade，避免个别机型/系统版本忽略主题里的 windowAnimationStyle 又滑进来
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** 统计页滚过浮岛后：状态栏从「透明沉浸」切成纸色+对应图标，浮岛钉在状态栏下沿 */
    public void setStatsScrolled(boolean scrolled) {
        if (!"stats".equals(current)) return;
        android.view.Window w = getWindow();
        boolean dark = Prefs.dark(this);
        int paper = getColor(dark ? R.color.paper_dark : R.color.paper);
        w.setStatusBarColor(scrolled ? paper : 0x00000000);
        try {
            androidx.core.view.WindowInsetsControllerCompat ctrl =
                    androidx.core.view.WindowCompat.getInsetsController(
                            w, w.getDecorView());
            ctrl.setAppearanceLightStatusBars(scrolled && !dark);
        } catch (Throwable ignored) {
        }
        int flags = w.getDecorView().getSystemUiVisibility();
        if (scrolled && !dark) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** 头图收起时把右上角昼夜钮藏起来，别压住浮岛 */
    public void setChromeVisible(boolean visible) {
        View t = findViewById(R.id.theme_toggle);
        if (t != null) t.setAlpha(visible ? 1f : 0f);
    }

    /**
     * 统计页：头图顶到状态栏下（沉浸）；离开时恢复内边距和状态栏配色。
     * 这里必须同步把内边距算好（WoolApp.setIgnoreTopInset），否则切 tab 会有一帧错位。
     */
    public void setStatsFullBleed(boolean on) {
        com.wool.ledger.WoolApp.setIgnoreTopInset(this, on);
        android.view.Window w = getWindow();
        android.view.View theme = findViewById(R.id.theme_toggle);
        if (on) {
            // 头图铺到状态栏下：状态栏透明 + 白色图标
            w.setStatusBarColor(0x00000000);
            w.getDecorView().setSystemUiVisibility(
                    w.getDecorView().getSystemUiVisibility()
                            & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            if (theme != null) {
                theme.setTranslationY(com.wool.ledger.WoolApp.statusBarHeight);
                theme.setAlpha(1f);
            }
        } else {
            // 回到普通页：状态栏跟纸色 + 主题对应的图标颜色 + 导航栏同色
            applyWindowTheme();
            if (theme != null) {
                theme.setTranslationY(0f);
                theme.setAlpha(1f);   // 统计页滚动时会把昼夜钮淡出，切页要恢复
            }
        }
    }

    /** 这个实例的 insets 回调（onDestroy 时只清自己那个，别把新实例的覆盖掉） */
    private final Runnable insetsCallback = this::syncWindowState;
    /** 只有前台的 Activity 才允许改窗口状态；切主题时旧实例会晚一步销毁，不能让它把状态改歪 */
    private boolean windowActive;

    @Override
    protected void onResume() {
        super.onResume();
        windowActive = true;
        // 重建/insets 都落定后再对一次（300ms 后），防止旧实例晚到的回调把状态弄歪
        getWindow().getDecorView().removeCallbacks(settleWindowState);
        getWindow().getDecorView().postDelayed(settleWindowState, 300);
        // 兜底：不管前面谁把状态弄歪，回到前台都按当前 tab 重新确认一次
        if (com.wool.ledger.WoolApp.onInsetsApplied == null) {
            com.wool.ledger.WoolApp.onInsetsApplied = insetsCallback;
        }
        syncWindowState();
    }

    @Override
    protected void onPause() {
        windowActive = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelTabCrossfade();
        if (com.wool.ledger.WoolApp.onInsetsApplied == insetsCallback) {
            com.wool.ledger.WoolApp.onInsetsApplied = null;
        }
        super.onDestroy();
    }

    /** 按当前 tab 把窗口状态（沉浸内边距 / 状态栏配色 / 统计页头图）对齐一遍，幂等 */
    /** 重建/insets 都落定后再对一次，覆盖"切主题后立刻切 tab"的竞态 */
    private final Runnable settleWindowState = () -> {
        if (!windowActive || isFinishing() || isDestroyed()) return;
        syncWindowState();
    };

    private void syncWindowState() {
        if (!windowActive || isFinishing() || isDestroyed()) return;
        setStatsFullBleed("stats".equals(current));
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.container);
        if (f instanceof StatsFragment) ((StatsFragment) f).syncHeroNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD && resultCode == RESULT_OK) {
            Fragment f = getSupportFragmentManager().findFragmentById(R.id.container);
            if (f instanceof HomeFragment) ((HomeFragment) f).refresh();
            if (f instanceof StatsFragment) ((StatsFragment) f).refresh();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    /** 把当前整窗（含背景）画到一张 bitmap，用于切主题时垫在涟漪下面 */
    private android.graphics.Bitmap captureWindowSnapshot() {
        android.view.View decor = getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            decor.draw(new android.graphics.Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 切深浅色：记住按钮位置 + 旧界面截图后重建，新界面从按钮位置圆形展开（涟漪外保持旧主题） */
    private void toggleTheme(android.view.View btn) {
        int[] loc = new int[2];
        btn.getLocationInWindow(loc);
        boolean wasDark = Prefs.dark(this);
        // 先切状态栏/导航栏（系统栏在 App 内容之上，不适合等涟漪），再让内容做圆形扩散：
        // 这样动画结束时不会出现「内容已经新主题、状态栏最后才跳一下」的突兀感。
        // 系统栏是叠在 App 上面的，光 setStatusBarColor 在 edge-to-edge/新系统上可能被忽略；
        // 同时把窗口底色换成新主题，状态栏下面那条底色才会立刻变。
        int newPaper = getColor(!wasDark ? R.color.paper_dark : R.color.paper);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(newPaper));
        applySystemBars(!wasDark, "stats".equals(current));
        android.graphics.Bitmap snap = captureWindowSnapshot();
        com.wool.ledger.util.ThemeReveal.remember(loc[0] + btn.getWidth() / 2,
                loc[1] + btn.getHeight() / 2, snap);

        // 图标动效：转半圈 + 缩一下再弹回，结束后再重建
        btn.animate().cancel();
        btn.setRotation(0f);
        btn.animate()
                .rotation(180f)
                .scaleX(0.78f)
                .scaleY(0.78f)
                .setDuration(190)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    Prefs.setDark(this, !Prefs.dark(this));
                    recreate();
                    // 旧界面已经由截图接管，别再叠一层 Activity 淡入淡出
                    overridePendingTransition(0, 0);
                })
                .start();
    }


    /** tab 切换的旧画面淡出：换页是瞬时的（保证标题不跳），旧截图盖在上面 180ms 淡掉 */
    private void playTabCrossfade(android.graphics.Bitmap snap) {
        cancelTabCrossfade();
        if (snap == null || snap.isRecycled() || isFinishing() || isDestroyed()) {
            if (snap != null && !snap.isRecycled()) snap.recycle();
            return;
        }
        try {
            android.widget.ImageView overlay = new android.widget.ImageView(this);
            overlay.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
            overlay.setImageBitmap(snap);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            ((android.view.ViewGroup) getWindow().getDecorView()).addView(overlay, lp);
            tabFadeOverlay = overlay;
            tabFadeBitmap = snap;
            overlay.setAlpha(1f);
            overlay.animate().alpha(0f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(this::cancelTabCrossfade)
                    .start();
        } catch (Throwable t) {
            if (!snap.isRecycled()) snap.recycle();
        }
    }

    private void cancelTabCrossfade() {
        if (tabFadeOverlay != null) {
            tabFadeOverlay.animate().cancel();
            android.view.ViewParent p = tabFadeOverlay.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(tabFadeOverlay);
            tabFadeOverlay.setImageDrawable(null);
            tabFadeOverlay = null;
        }
        if (tabFadeBitmap != null && !tabFadeBitmap.isRecycled()) tabFadeBitmap.recycle();
        tabFadeBitmap = null;
    }

    /** 状态栏 / 导航栏 / 窗口底色：跟随当前主题（动画结束后调用，避免中途变色） */
    private void applyWindowTheme() {
        if (revealRunning) return;   // 涟漪动画期间窗口底色/系统栏仍是旧主题
        boolean dark = Prefs.dark(this);
        int paper = getColor(dark ? R.color.paper_dark : R.color.paper);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(paper));
        applySystemBars(dark, "stats".equals(current));
    }

    /** 底部导航跟着换深浅色 */
    private void syncNavTheme(boolean dark) {
        boolean animate = false;
        syncNav(animate);
        View bar = findViewById(R.id.nav_bar);
        if (bar != null) bar.setBackgroundResource(dark ? R.drawable.bg_nav_dark : R.drawable.bg_nav);
        View ai = findViewById(R.id.nav_ai);
        if (ai != null) ai.setBackgroundResource(R.drawable.bg_ai_face);
        View ind = findViewById(R.id.nav_indicator);
        if (ind != null) ind.setBackgroundResource(dark ? R.drawable.bg_nav_item_on_dark : R.drawable.bg_nav_item_on);
    }




    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_CURRENT, current);
    }
}
