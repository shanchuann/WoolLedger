package com.wool.ledger.automation;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.*;

import com.wool.ledger.data.Prefs;

import java.util.concurrent.*;

public class PaymentNotificationService extends NotificationListenerService {
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !Prefs.autoCapture(this) || !Prefs.captures(this, sbn.getPackageName()))
            return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle e = n.extras;
        StringBuilder s = new StringBuilder();
        CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE);
        if (title != null) s.append(title).append('\n');
        if ("com.tencent.mm".equals(sbn.getPackageName())
                && (title == null || !title.toString().matches(".*(微信支付|微信收款|收款通知|支付凭证).*")))
            return;
        CharSequence body = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (body == null) body = e.getCharSequence(Notification.EXTRA_TEXT);
        if (body != null) s.append(body);
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null)
            for (CharSequence line : lines) if (line != null) s.append('\n').append(line);
        String raw = s.toString();
        if (raw.length() > 3000) raw = raw.substring(0, 3000);
        final String text = raw;
        try {
            io.execute(
                    () -> {
                        try {
                            new PaymentStore(this)
                                    .receive(
                                            sbn.getPackageName(),
                                            sbn.getKey(),
                                            text,
                                            sbn.getPostTime());
                        } catch (Exception failure) {
                            getSharedPreferences("capture_health", MODE_PRIVATE)
                                    .edit()
                                    .putLong("last_failure", System.currentTimeMillis())
                                    .apply();
                        }
                    });
        } catch (RejectedExecutionException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
