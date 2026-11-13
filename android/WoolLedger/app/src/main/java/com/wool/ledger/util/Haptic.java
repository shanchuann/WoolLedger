package com.wool.ledger.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * 统一的触感反馈：优先用 Vibrator 真震，取不到震动器时退回 View 的 haptic 反馈。
 * 轻点 12ms、长按 28ms、确认 18ms，都遵守系统「触感反馈」开关。
 */
public class Haptic {

    private static Vibrator vib(Context c) {
        if (c == null) return null;
        try {
            return (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    private static void vibrate(View v, long ms, int fallback) {
        Context ctx = v == null ? null : v.getContext();
        Vibrator vib = vib(ctx);
        if (vib != null && vib.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vib.vibrate(ms);
                }
                return;
            } catch (Exception ignored) {
            }
        }
        if (v != null) v.performHapticFeedback(fallback);
    }

    public static void tap(View v) {
        vibrate(v, 12, HapticFeedbackConstants.VIRTUAL_KEY);
    }

    public static void longPress(View v) {
        vibrate(v, 28, HapticFeedbackConstants.LONG_PRESS);
    }

    public static void confirm(View v) {
        vibrate(v, 18, HapticFeedbackConstants.CONFIRM);
    }
}
