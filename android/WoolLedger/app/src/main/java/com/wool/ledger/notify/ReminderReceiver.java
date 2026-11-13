package com.wool.ledger.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.wool.ledger.MainActivity;
import com.wool.ledger.R;

/** 每日提醒：发一条「该记账了」的通知。 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL = "wool.reminder";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "每日记账提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_nav_add)
                .setContentTitle("该记账了")
                .setContentText("说一句就行，比如「午饭食堂 15」")
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            nm.notify(1001, b.build());
        } catch (Exception ignored) {
        }
    }
}
