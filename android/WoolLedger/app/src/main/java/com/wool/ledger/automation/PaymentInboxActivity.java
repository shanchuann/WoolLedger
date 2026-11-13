package com.wool.ledger.automation;

import android.app.AlertDialog;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

import com.wool.ledger.agent.LedgerCodec;
import com.wool.ledger.data.*;
import com.wool.ledger.util.*;

import java.util.*;
import java.util.concurrent.*;

public class PaymentInboxActivity extends AppCompatActivity {
    private LinearLayout body;
    private PaymentStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean history;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Prefs.applyTheme(this);
        store = new PaymentStore(this);
        history = saved != null && saved.getBoolean("history");
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) render();
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putBoolean("history", history);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(ThemeUtil.ink(this));
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private void button(String s, Runnable run) {
        TextView t = text(s + "  ›", 16);
        t.setBackgroundColor(ThemeUtil.card(this));
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        body.addView(t, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> run.run());
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ThemeUtil.paper(this));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(body);
        setContentView(scroll);
        button("‹ 返回", this::finish);
        body.addView(text("自动记账", 28));
        boolean granted =
                NotificationManagerCompat.getEnabledListenerPackages(this)
                        .contains(getPackageName());
        body.addView(
                text(
                        "通知读取："
                                + (granted ? "已授权" : "未授权")
                                + " · "
                                + (Prefs.autoCapture(this) ? "已开启" : "已关闭"),
                        14));
        body.addView(
                text(
                        "只处理你选中的应用新发出的交易通知。通知原文仅保存在本机，不上传 AI；缺失信息和疑似重复需要你确认。支付软件未发通知或隐藏金额时无法自动记录。",
                        14));
        button(
                Prefs.autoCapture(this) ? "关闭自动记账" : "开启自动记账",
                () -> {
                    if (Prefs.autoCapture(this)) {
                        Prefs.setAutoCapture(this, false);
                        render();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("开启支付通知读取？")
                            .setMessage(
                                    "系统授权可能允许读取通知内容。羊毛账本仅处理所选支付应用中的交易通知，提取金额、商户和时间用于记账，并在本机保存最多1000字通知片段。不会读取聊天内容作为账目或上传原文。你可以随时关闭功能或撤销系统授权。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton(
                                    "同意并开启",
                                    (d, w) -> {
                                        Prefs.setAutoCapture(this, true);
                                        openAccess();
                                        render();
                                    })
                            .show();
                });
        button("管理系统通知使用权", this::openAccess);
        button(
                "明确交易：" + (Prefs.autoPost(this) ? "自动入账" : "也需确认"),
                () -> {
                    Prefs.setAutoPost(this, !Prefs.autoPost(this));
                    render();
                });
        button("选择支付应用", this::packages);
        String failure =
                getSharedPreferences("capture_health", MODE_PRIVATE).getLong("last_failure", 0) > 0
                        ? "\n曾有通知处理失败，请用账单核对漏账。"
                        : "";
        body.addView(
                text(
                        "微信、支付宝、云闪付提供通用规则；各软件版本的通知格式需要实际验证。可添加银行应用包名。退款、转账和还款只进入待确认，请确认是否属于消费，避免重复计算。"
                                + failure,
                        13));
        button(
                history ? "查看待确认" : "查看处理记录 / 撤销",
                () -> {
                    history = !history;
                    render();
                });
        final LinearLayout target = body;
        target.addView(text(history ? "处理记录（最近100条）" : "待确认", 21));
        io.execute(
                () -> {
                    List<ContentValues> rows = store.list(history ? null : "pending");
                    runOnUiThread(
                            () -> {
                                if (isFinishing() || body != target) return;
                                for (ContentValues e : rows) addEvent(e);
                                if (rows.isEmpty())
                                    body.addView(text("暂无记录。授权后收到可识别交易通知才会出现在这里。", 15));
                            });
                });
    }

    private void openAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "请在系统设置中搜索「通知使用权」", Toast.LENGTH_LONG).show();
        }
    }

    private void packages() {
        String[] names = {"微信", "支付宝", "云闪付"},
                pkgs = {"com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay"};
        boolean[] checked = new boolean[3];
        for (int i = 0; i < 3; i++) checked[i] = Prefs.captures(this, pkgs[i]);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        CheckBox[] boxes = new CheckBox[3];
        for (int i = 0; i < 3; i++) {
            boxes[i] = new CheckBox(this);
            boxes[i].setText(names[i]);
            boxes[i].setChecked(checked[i]);
            layout.addView(boxes[i]);
        }
        EditText custom = new EditText(this);
        custom.setHint("其他支付/银行应用包名，逗号分隔");
        String current = Prefs.capturePackages(this);
        for (String p : pkgs) current = current.replace(p, "");
        custom.setText(current.replaceAll("^,+|,+$", ""));
        layout.addView(custom);
        new AlertDialog.Builder(this)
                .setTitle("仅读取选中应用")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (d, w) -> {
                            List<String> list = new ArrayList<>();
                            for (int i = 0; i < 3; i++) if (boxes[i].isChecked()) list.add(pkgs[i]);
                            for (String p : custom.getText().toString().split("[,，\\s]+"))
                                if (p.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
                                        && !p.equals(getPackageName())) list.add(p);
                            Prefs.setCapturePackages(this, android.text.TextUtils.join(",", list));
                            render();
                        })
                .show();
    }

    private void addEvent(ContentValues e) {
        long id = e.getAsLong("id");
        String state = e.getAsString("state");
        String label =
                state.equals("posted") ? "已入账" : state.equals("ignored") ? "已忽略 / 已撤销" : "待确认";
        body.addView(
                text(
                        label
                                + " · "
                                + e.getAsString("package_name")
                                + "\n¥"
                                + Money.fmt(e.getAsDouble("amount"))
                                + " · "
                                + e.getAsString("merchant")
                                + "\n"
                                + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                                        .format(new Date(e.getAsLong("posted")))
                                + "\n"
                                + e.getAsString("reason"),
                        16));
        body.addView(text(e.getAsString("raw"), 13));
        if (state.equals("pending")) {
            button("核对并记账", () -> edit(e));
            button("忽略这条", () -> run(() -> store.ignore(id)));
        } else if (state.equals("posted"))
            button(
                    "撤销这笔通知入账",
                    () ->
                            new AlertDialog.Builder(this)
                                    .setMessage("撤销后将删除对应账目并保留去重标记。已被修改的账目需到详情处理。")
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("撤销", (d, w) -> run(() -> store.undo(id)))
                                    .show());
        View line = new View(this);
        line.setBackgroundColor(ThemeUtil.ink3(this));
        body.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private EditText field(LinearLayout form, String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        form.addView(text(hint, 13));
        form.addView(e);
        return e;
    }

    private void edit(ContentValues event) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), 0, dp(18), 0);
        Bill b =
                PaymentStore.bill(
                        event.getAsDouble("amount"),
                        event.getAsInteger("type"),
                        event.getAsString("category"),
                        event.getAsString("merchant"),
                        event.getAsLong("posted"));
        EditText amount =
                field(
                        form,
                        "实际金额",
                        b.amount > 0 ? String.valueOf(b.amount) : "",
                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note = field(form, "商户 / 备注", b.note, InputType.TYPE_CLASS_TEXT);
        EditText date = field(form, "交易日期 yyyy-MM-dd", b.billDate, InputType.TYPE_CLASS_DATETIME);
        EditText time = field(form, "交易时间 HH:mm", b.billTime, InputType.TYPE_CLASS_DATETIME);
        Spinner category = new Spinner(this);
        String[] cats = new String[Category.ALL.size()];
        int selected = 0;
        for (int i = 0; i < cats.length; i++) {
            Category c = Category.ALL.get(i);
            cats[i] = (c.type == 1 ? "收入 · " : "支出 · ") + c.name;
            if (c.code.equals(b.category)) selected = i;
        }
        category.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cats));
        category.setSelection(selected);
        form.addView(text("收支与分类（资金划转可直接忽略）", 13));
        form.addView(category);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("核对通知后记账")
                        .setView(scroll)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确认记账", null)
                        .create();
        dialog.setOnShowListener(
                x ->
                        dialog.getButton(-1)
                                .setOnClickListener(
                                        v -> {
                                            try {
                                                b.amount =
                                                        Double.parseDouble(
                                                                amount.getText().toString());
                                                b.note = note.getText().toString().trim();
                                                b.billDate = date.getText().toString().trim();
                                                b.billTime = time.getText().toString().trim();
                                                b.slot = DateUtil.slotOf(b.billTime);
                                                Category c =
                                                        Category.ALL.get(
                                                                category.getSelectedItemPosition());
                                                b.category = c.code;
                                                b.type = c.type;
                                                LedgerCodec.validate(b);
                                                dialog.getButton(-1).setEnabled(false);
                                                io.execute(
                                                        () -> {
                                                            try {
                                                                store.confirm(
                                                                        event.getAsLong("id"), b);
                                                                runOnUiThread(
                                                                        () -> {
                                                                            dialog.dismiss();
                                                                            render();
                                                                        });
                                                            } catch (Exception failure) {
                                                                runOnUiThread(
                                                                        () -> {
                                                                            dialog.getButton(-1)
                                                                                    .setEnabled(
                                                                                            true);
                                                                            Toast.makeText(
                                                                                            this,
                                                                                            failure
                                                                                                    .getMessage(),
                                                                                            Toast
                                                                                                    .LENGTH_LONG)
                                                                                    .show();
                                                                        });
                                                            }
                                                        });
                                            } catch (Exception invalid) {
                                                Toast.makeText(
                                                                this,
                                                                invalid.getMessage(),
                                                                Toast.LENGTH_LONG)
                                                        .show();
                                            }
                                        }));
        dialog.show();
    }

    private void run(Runnable work) {
        io.execute(
                () -> {
                    try {
                        work.run();
                        runOnUiThread(this::render);
                    } catch (Exception e) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG)
                                                .show());
                    }
                });
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
