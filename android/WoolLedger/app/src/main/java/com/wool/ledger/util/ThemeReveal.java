package com.wool.ledger.util;

import android.graphics.Bitmap;

/**
 * 深浅色切换的圆形扩散：重建 Activity 前后传位置 + 旧界面截图。
 *
 * 旧界面截图在 recreate 前抓一张，新 Activity 里先把它铺成窗口背景，
 * 再对新内容做圆形 reveal；这样涟漪圆外面还是旧主题，圆里面才是新主题。
 */
public class ThemeReveal {

    public static boolean pending = false;
    public static int cx = 0;
    public static int cy = 0;
    /** 切换前整窗截图（新 Activity 里当窗口背景用） */
    public static Bitmap snapshot = null;

    public static void remember(int x, int y, Bitmap bmp) {
        if (snapshot != null && snapshot != bmp) snapshot.recycle();
        pending = true;
        cx = x;
        cy = y;
        snapshot = bmp;
    }

    /** 消费掉位置信息（截图留给动画结束再释放） */
    public static void clearPending() {
        pending = false;
    }

    public static void releaseSnapshot() {
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
        }
    }
}
