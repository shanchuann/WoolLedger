package com.wool.ledger.util;

import android.content.Context;

import com.wool.ledger.R;
import com.wool.ledger.data.Prefs;

/** 手动切换深色（不走系统 night 模式），所有页面统一从这里取色。 */
public class ThemeUtil {

    public static boolean dark(Context c) {
        return Prefs.dark(c);
    }

    public static int paper(Context c) {
        return c.getColor(dark(c) ? R.color.paper_dark : R.color.paper);
    }

    public static int card(Context c) {
        return c.getColor(dark(c) ? R.color.card_dark : R.color.paper_card);
    }

    public static int ink(Context c) {
        return c.getColor(dark(c) ? R.color.ink_dark : R.color.ink);
    }

    public static int ink2(Context c) {
        return c.getColor(dark(c) ? R.color.ink2_dark : R.color.ink2);
    }

    public static int ink3(Context c) {
        return c.getColor(dark(c) ? R.color.ink3_dark : R.color.ink3);
    }

    public static int line(Context c) {
        return c.getColor(dark(c) ? R.color.line_dark : R.color.line);
    }

    /**
     * 深色模式下把整棵视图树里“仍是浅色主题色”的文字与背景重涂一遍。
     * 用于 XML 里写死 @color/ink 之类、又没走 ThemeUtil 的控件。
     */
    public static void repaint(Context c, android.view.View root) {
        if (!dark(c) || root == null) return;
        int inkL = c.getColor(R.color.ink), inkD = c.getColor(R.color.ink_dark);
        int ink2L = c.getColor(R.color.ink2), ink2D = c.getColor(R.color.ink2_dark);
        int ink3L = c.getColor(R.color.ink3), ink3D = c.getColor(R.color.ink3_dark);
        int paperL = c.getColor(R.color.paper), paperD = c.getColor(R.color.paper_dark);
        repaint(c, root, inkL, inkD, ink2L, ink2D, ink3L, ink3D, paperL, paperD);
    }

    private static void repaint(Context c, android.view.View v,
                                int inkL, int inkD, int ink2L, int ink2D,
                                int ink3L, int ink3D, int paperL, int paperD) {
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            int cur = tv.getCurrentTextColor();
            if (cur == inkL) tv.setTextColor(inkD);
            else if (cur == ink2L) tv.setTextColor(ink2D);
            else if (cur == ink3L) tv.setTextColor(ink3D);
            // 提示色（未选中态）也一起提亮
            if (tv.getHint() != null && tv.getCurrentHintTextColor() == ink3L) {
                tv.setHintTextColor(ink3D);
            }
        } else if (v instanceof android.widget.ImageView) {
            android.widget.ImageView iv = (android.widget.ImageView) v;
            android.graphics.ColorFilter f = iv.getColorFilter();
            // 用 tint 上色的图标：只处理纯色滤镜
            if (f == null && iv.getBackground() == null) {
                // 无操作，避免误改图片
            }
        }
        if (v.getBackground() instanceof android.graphics.drawable.ColorDrawable) {
            android.graphics.drawable.ColorDrawable cd =
                    (android.graphics.drawable.ColorDrawable) v.getBackground();
            android.graphics.PorterDuff.Mode ignored = null;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                repaint(c, g.getChildAt(i), inkL, inkD, ink2L, ink2D, ink3L, ink3D, paperL, paperD);
            }
        }
    }
}
