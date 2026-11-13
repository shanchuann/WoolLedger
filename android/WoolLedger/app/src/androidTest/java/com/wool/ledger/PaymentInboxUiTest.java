package com.wool.ledger;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;

import androidx.test.platform.app.InstrumentationRegistry;

import com.wool.ledger.automation.*;
import com.wool.ledger.data.*;

import org.junit.*;

import java.io.*;
import java.util.*;

/** 真正点击自定义控件，核验取消、校验、确认与撤销后的持久化状态。 */
public class PaymentInboxUiTest {
    private Instrumentation inst;
    private Context ctx;
    private PaymentInboxActivity activity;
    private String oldPackages;
    private boolean oldCapture, oldPost, oldDark;

    @Before
    public void setup() {
        inst = InstrumentationRegistry.getInstrumentation();
        ctx = inst.getTargetContext();
        oldPackages = Prefs.capturePackages(ctx);
        oldCapture = Prefs.autoCapture(ctx);
        oldPost = Prefs.autoPost(ctx);
        oldDark = Prefs.dark(ctx);
        new BillDao(ctx).clearAll();
        Prefs.setAutoCapture(ctx, false);
        Prefs.setAutoPost(ctx, true);
        Prefs.setCapturePackages(ctx, "com.tencent.mm,com.eg.android.AlipayGphone,com.unionpay");
        Prefs.setDark(ctx, false);
    }

    @After
    public void close() {
        if (activity != null) inst.runOnMainSync(() -> activity.finish());
        Prefs.setCapturePackages(ctx, oldPackages);
        Prefs.setAutoCapture(ctx, oldCapture);
        Prefs.setAutoPost(ctx, oldPost);
        Prefs.setDark(ctx, oldDark);
    }

    private void start() {
        activity =
                (PaymentInboxActivity)
                        inst.startActivitySync(
                                new Intent(ctx, PaymentInboxActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        inst.waitForIdleSync();
    }

    private View page() {
        return activity.getWindow().getDecorView();
    }

    private View find(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = find(g.getChildAt(i), text);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void click(View root, String label) {
        inst.runOnMainSync(
                () -> {
                    View v = find(root, label);
                    assertNotNull("Missing " + label, v);
                    while (!v.isClickable()) v = (View) v.getParent();
                    v.performClick();
                });
        inst.waitForIdleSync();
    }

    @SuppressWarnings("unchecked")
    private Dialog dialog() throws Exception {
        java.lang.reflect.Field f = PaymentInboxActivity.class.getDeclaredField("dialogs");
        f.setAccessible(true);
        List<Dialog> list = (List<Dialog>) f.get(activity);
        assertFalse("Expected custom dialog", list.isEmpty());
        return list.get(list.size() - 1);
    }

    private List<EditText> inputs(View v) {
        List<EditText> out = new ArrayList<>();
        if (v instanceof EditText) out.add((EditText) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) out.addAll(inputs(g.getChildAt(i)));
        }
        return out;
    }

    private void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            inst.waitForIdleSync();
            if (condition.call()) return;
            Thread.sleep(50);
        }
        fail("Timed out");
    }

    private void screenshot(String name) throws Exception {
        inst.waitForIdleSync();
        Thread.sleep(600);
        Bitmap bitmap = inst.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir = new File(ctx.getExternalFilesDir(null), "ui-review");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }

    @Test
    public void selectionCancelAndInvalidInputDoNotChangePreferences() throws Exception {
        start();
        await(() -> find(page(), "Payment Inbox").getWidth() > 0);
        screenshot("payment-light");
        click(page(), "支付应用");
        Dialog first = dialog();
        screenshot("payment-apps-light");
        click(first.getWindow().getDecorView(), "微信");
        click(first.getWindow().getDecorView(), "取消");
        assertTrue(Prefs.captures(ctx, "com.tencent.mm"));
        click(page(), "支付应用");
        Dialog d = dialog();
        View root = d.getWindow().getDecorView();
        EditText custom = inputs(root).get(0);
        inst.runOnMainSync(() -> custom.setText("invalid package"));
        click(root, "保存选择");
        assertTrue(d.isShowing());
        assertEquals(3, Prefs.capturePackages(ctx).split(",").length);
        inst.runOnMainSync(() -> custom.setText("com.example.bank"));
        click(root, "保存选择");
        assertFalse(d.isShowing());
        assertTrue(Prefs.captures(ctx, "com.example.bank"));
        // Popup choice changes the preference only after an explicit selection.
        click(page(), "入账方式");
        assertTrue(Prefs.autoPost(ctx));
        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        assertTrue(Prefs.autoPost(ctx));
        click(page(), "入账方式");
        screenshot("payment-mode-light");
        android.view.accessibility.AccessibilityNodeInfo window =
                inst.getUiAutomation().getRootInActiveWindow();
        boolean picked = false;
        for (android.view.accessibility.AccessibilityNodeInfo node :
                window.findAccessibilityNodeInfosByText("全部先确认")) {
            android.view.accessibility.AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null)
                picked =
                        target.performAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        }
        assertTrue(picked);
        await(() -> !Prefs.autoPost(ctx));
    }

    @Test
    public void darkFormValidatesThenConfirmsAndUndoRequiresConfirmation() throws Exception {
        Prefs.setDark(ctx, true);
        Prefs.setAutoCapture(ctx, true);
        Prefs.setAutoPost(ctx, false);
        PaymentStore store = new PaymentStore(ctx);
        store.receive(
                "com.eg.android.AlipayGphone",
                "ui-test",
                "支付成功 商户：食堂 金额：¥15.00",
                System.currentTimeMillis());
        assertEquals(1, store.pendingCount());
        start();
        await(() -> find(page(), "核对并记账") != null);
        screenshot("payment-dark");
        click(page(), "核对并记账");
        Dialog d = dialog();
        View form = d.getWindow().getDecorView();
        screenshot("payment-form-dark");
        EditText amount = inputs(form).get(0);
        inst.runOnMainSync(
                () -> {
                    amount.requestFocus();
                    ((android.view.inputmethod.InputMethodManager)
                                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE))
                            .showSoftInput(
                                    amount,
                                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                });
        screenshot("payment-form-keyboard-dark");
        inst.runOnMainSync(() -> amount.setText(""));
        click(form, "确认记账");
        assertTrue(d.isShowing());
        assertEquals(1, store.pendingCount());
        assertTrue(new BillDao(ctx).isEmpty());
        inst.runOnMainSync(() -> amount.setText("15"));
        click(form, "确认记账");
        await(() -> !d.isShowing());
        assertEquals(1, new BillDao(ctx).listAll().size());
        click(page(), "处理记录");
        await(() -> find(page(), "撤销这笔入账") != null);
        click(page(), "撤销这笔入账");
        Dialog undo = dialog();
        screenshot("payment-undo-dark");
        click(undo.getWindow().getDecorView(), "保留账目");
        assertEquals(1, new BillDao(ctx).listAll().size());
        click(page(), "撤销这笔入账");
        click(dialog().getWindow().getDecorView(), "确认撤销");
        await(() -> new BillDao(ctx).isEmpty());
    }

    @Test
    public void consentCancellationDoesNotEnableReading() throws Exception {
        start();
        inst.runOnMainSync(
                () -> {
                    ViewGroup root = (ViewGroup) page();
                    assertTrue(clickCaptureRow(root));
                });
        Dialog consent = dialog();
        screenshot("payment-consent-light");
        click(consent.getWindow().getDecorView(), "暂不开启");
        assertFalse(Prefs.autoCapture(ctx));
    }

    private boolean clickCaptureRow(View v) {
        if (v instanceof LinearLayout && find(v, "已关闭") != null && v.isClickable()) {
            v.performClick();
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                if (clickCaptureRow(g.getChildAt(i))) return true;
        }
        return false;
    }
}
