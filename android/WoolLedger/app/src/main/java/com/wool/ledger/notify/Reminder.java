package com.wool.ledger.notify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** 每日提醒调度（setInexactRepeating，不需要精确闹钟权限）。 */
public class Reminder {

    public static void schedule(Context ctx, String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) {
            cancel(ctx);
            return;
        }
        int h = 21, m = 0;
        try {
            h = Integer.parseInt(hhmm.substring(0, hhmm.indexOf(':')));
            m = Integer.parseInt(hhmm.substring(hhmm.indexOf(':') + 1));
        } catch (Exception ignored) {
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, ReminderReceiver.class);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, i,
                PendingIntent.FLAG_UPDATE_CURRENT | flags);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, ReminderReceiver.class);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, i,
                PendingIntent.FLAG_UPDATE_CURRENT | flags);
        am.cancel(pi);
    }
}
