package com.wool.ledger;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.ParcelFileDescriptor;

import androidx.test.platform.app.InstrumentationRegistry;

import com.wool.ledger.automation.*;
import com.wool.ledger.data.*;

import org.junit.*;

/** Posts a synthetic OS notification; it does not impersonate a real payment app. */
public class NotificationPipelineTest {
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor fd =
                        InstrumentationRegistry.getInstrumentation()
                                .getUiAutomation()
                                .executeShellCommand(command);
                java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] b = new byte[1024];
            while (in.read(b) != -1) {}
        }
    }

    @Test
    public void operatingSystemNotificationReachesLedger() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BillDao dao = new BillDao(ctx);
        dao.clearAll();
        DbHelper.get(ctx).getWritableDatabase().delete("payment_event", null, null);
        Prefs.setCapturePackages(ctx, ctx.getPackageName());
        Prefs.setAutoCapture(ctx, true);
        Prefs.setAutoPost(ctx, true);
        NotificationManager manager =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            shell("pm grant " + ctx.getPackageName() + " android.permission.POST_NOTIFICATIONS");
            shell(
                    "cmd notification allow_listener "
                            + ctx.getPackageName()
                            + "/.automation.PaymentNotificationService");
            Thread.sleep(1000);
            manager.createNotificationChannel(
                    new NotificationChannel(
                            "test-payment",
                            "Synthetic payment test",
                            NotificationManager.IMPORTANCE_DEFAULT));
            Notification n =
                    new Notification.Builder(ctx, "test-payment")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle("支付成功")
                            .setStyle(
                                    new Notification.BigTextStyle()
                                            .bigText("商户：测试食堂\n15.00元 交易号：OSPIPE12345"))
                            .build();
            manager.notify(9081, n);
            long end = System.currentTimeMillis() + 10000;
            while (dao.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(100);
            assertEquals(1, dao.listAll().size());
            assertEquals(3, dao.listAll().get(0).source);
            manager.notify(9081, n);
            Thread.sleep(500);
            assertEquals(1, dao.listAll().size());
        } finally {
            manager.cancel(9081);
            shell(
                    "cmd notification disallow_listener "
                            + ctx.getPackageName()
                            + "/.automation.PaymentNotificationService");
            Prefs.setAutoCapture(ctx, false);
            Prefs.setCapturePackages(
                    ctx, "com.tencent.mm,com.eg.android.AlipayGphone,com.unionpay");
        }
    }
}
