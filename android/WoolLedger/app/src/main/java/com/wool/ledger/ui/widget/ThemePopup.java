package com.wool.ledger.ui.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wool.ledger.util.Haptic;
import com.wool.ledger.util.ThemeUtil;

/**
 * 跟主题走的小下拉菜单：圆角纸卡、当前项加粗带底色、点外面收起。
 * 统计页的「时间范围 / 支出条目 / 比较之前」都用它。
 */
public final class ThemePopup {

    public interface OnPick {
        void onPick(int index);
    }

    private ThemePopup() {
    }

    public static void show(final Context c, final View anchor, String[] labels, int selected, final OnPick cb) {
        if (c == null || anchor == null || labels == null || labels.length == 0) return;
        final boolean dark = ThemeUtil.dark(c);
        float d = c.getResources().getDisplayMetrics().density;
        int pad = Math.round(6 * d);
        int rowH = Math.round(42 * d);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ThemeUtil.card(c));
        bg.setCornerRadius(16 * d);
        bg.setStroke(Math.max(1, Math.round(d)), ThemeUtil.line(c));
        box.setBackground(bg);

        final PopupWindow[] holder = new PopupWindow[1];
        int ink = ThemeUtil.ink(c);
        int ink2 = ThemeUtil.ink2(c);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == selected;
            TextView row = new TextView(c);
            row.setText(labels[i]);
            row.setTextSize(13.5f);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setIncludeFontPadding(false);
            row.setPadding(Math.round(12 * d), 0, Math.round(12 * d), 0);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowH));
            row.setTextColor(on ? ink : ink2);
            row.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            if (on) {
                GradientDrawable sel = new GradientDrawable();
                sel.setColor(dark ? 0x14FFFFFF : 0x0F1A1F24);
                sel.setCornerRadius(10 * d);
                row.setBackground(sel);
            }
            row.setOnClickListener(v -> {
                Haptic.tap(v);
                if (holder[0] != null) holder[0].dismiss();
                cb.onPick(idx);
            });
            box.addView(row);
        }

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(box);

        int width = Math.max(anchor.getWidth(), Math.round(168 * d));
        int contentH = labels.length * rowH + 2 * pad;
        int maxH = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.6f);
        PopupWindow p = new PopupWindow(scroll, width, Math.min(contentH, maxH), true);
        p.setBackgroundDrawable(new ColorDrawable(0x00000000));
        p.setOutsideTouchable(true);
        p.setElevation(12 * d);
        p.showAsDropDown(anchor, 0, Math.round(6 * d));
        holder[0] = p;
    }
}
