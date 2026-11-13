package com.wool.ledger;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 全局处理系统栏内边距：Android 15（targetSdk 35）强制 edge-to-edge，
 * 这里给每个 Activity 的内容根加状态栏 / 手势条内边距，页面自己不用管。
 *
 * 统计页要"头图铺到状态栏下面"，所以状态是可切换的；切换时**同步**算好内边距，
 * 不能只依赖下一次 insets 分发，否则切 tab 那一帧会闪/错位。
 */
public class WoolApp extends Application {

    /** 状态栏高度（px），统计页做沉浸时用 */
    public static int statusBarHeight = 0;
    /** 左右 / 底部安全区 */
    public static int insetLeft = 0, insetRight = 0, insetBottom = 0;
    /** true 时内容不再给状态栏让出顶部内边距（统计页头图铺到状态栏下面） */
    public static boolean ignoreTopInset = false;
    /** insets 每次应用后回调（MainActivity 用来按当前 tab 重新确认一次状态，防竞态） */
    public static Runnable onInsetsApplied = null;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                // 显式声明 edge-to-edge：Activity.recreate()（切深浅色）时系统可能把窗口
                // 退回 "fitsSystemWindows" 布局，导致状态栏/导航栏内边距被系统再加一遍。
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(a.getWindow(), false);
                final View content = a.findViewById(android.R.id.content);
                if (content == null) return;
                ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    statusBarHeight = bars.top;
                    insetLeft = bars.left;
                    insetRight = bars.right;
                    insetBottom = bars.bottom;
                    // 只认这个 Activity 自己的沉浸标记：全站静态变量会让统计页的沉浸状态
                    // 泄漏到从统计页打开的其它 Activity（标题整体上移一条状态栏）
                    boolean ignoreTop = Boolean.TRUE.equals(v.getTag());
                    v.setPadding(insetLeft, ignoreTop ? 0 : statusBarHeight, insetRight, insetBottom);
                    // 新一轮 insets（比如切主题重建后）可能把内边距改回默认，这里让 Activity 再对齐一次
                    if (onInsetsApplied != null) onInsetsApplied.run();
                    return insets;
                });
                ViewCompat.requestApplyInsets(content);
            }

            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    /** 切 tab / 重建时立刻把内容根的内边距算成目标状态（同步，不等 insets 回调） */
    public static void setIgnoreTopInset(Activity a, boolean ignore) {
        ignoreTopInset = ignore;
        if (a == null) return;
        View content = a.findViewById(android.R.id.content);
        if (content == null) return;
        content.setTag(ignore ? Boolean.TRUE : null);
        if (statusBarHeight > 0 || ignore) {
            content.setPadding(insetLeft, ignore ? 0 : statusBarHeight, insetRight, insetBottom);
            content.requestLayout();
        } else {
            ViewCompat.requestApplyInsets(content);
        }
    }
}
