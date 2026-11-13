package com.wool.ledger.util;

import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/** 数字滚动：金额从旧值补间到新值，和网页版的 animateNumber 一致。 */
public class Anim {

    public static void count(final TextView tv, final double from, final double to, final String prefix) {
        if (tv == null) return;
        ValueAnimator a = ValueAnimator.ofFloat((float) from, (float) to);
        a.setDuration(560);
        a.setInterpolator(new DecelerateInterpolator(2.2f));
        a.addUpdateListener(an -> {
            float v = (float) an.getAnimatedValue();
            tv.setText(prefix + Money.fmt(v));
        });
        a.start();
    }

    public static int dp(android.content.Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
