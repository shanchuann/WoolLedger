package com.wool.ledger.ui.detail;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.wool.ledger.R;
import com.wool.ledger.data.Bill;
import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.ui.add.AddRecordActivity;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Anim;
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 账单详情：一笔账的金额、坐标（周几 · 时段）与七行溯源信息，底部可编辑 / 删除，
 * 末尾列出这一格的其他笔。DAO 调用全部在后台线程，结果回主线程渲染。
 */
public class BillDetailActivity extends AppCompatActivity {

    private static final int REQ_EDIT = 1001;

    private HandlerThread ioThread;
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BillDao dao;
    private long billId;
    private Bill bill;
    private boolean changed;

    private TextView subtitle;
    private TextView catText;
    private TextView amountText;
    private TextView coordText;
    private TextView noteText;
    private TextView dateText;
    private TextView timeText;
    private TextView sourceText;
    private TextView createdText;
    private TextView rawText;
    private TextView sameCount;
    private LinearLayout sameList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.applyTheme(this);
        setContentView(R.layout.activity_bill_detail);

        billId = getIntent().getLongExtra("bill_id", 0L);
        subtitle = findViewById(R.id.detail_sub);
        catText = findViewById(R.id.detail_cat);
        amountText = findViewById(R.id.detail_amount);
        coordText = findViewById(R.id.detail_coord);
        noteText = findViewById(R.id.detail_note);
        dateText = findViewById(R.id.detail_date);
        timeText = findViewById(R.id.detail_time);
        sourceText = findViewById(R.id.detail_source);
        createdText = findViewById(R.id.detail_created);
        rawText = findViewById(R.id.detail_raw);
        sameCount = findViewById(R.id.detail_same_count);
        sameList = findViewById(R.id.detail_same_list);

        findViewById(R.id.btn_detail_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_detail_edit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edit();
            }
        });
        findViewById(R.id.btn_detail_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });

        ioThread = new HandlerThread("bill-detail-io");
        ioThread.start();
        bg = new Handler(ioThread.getLooper());
        dao = new BillDao(this);

        if (billId <= 0) {
            Toast.makeText(this, "这一笔不存在", Toast.LENGTH_SHORT).show();
            finish();
        }

        applyThemeColors();
    }

    /** 深色（Prefs.dark）统一从 ThemeUtil 取色，浅色时与原版一致 */
    private void applyThemeColors() {
        boolean dark = ThemeUtil.dark(this);
        findViewById(R.id.detail_root).setBackgroundColor(ThemeUtil.paper(this));
        ((TextView) findViewById(R.id.detail_title)).setTextColor(ThemeUtil.ink(this));
        subtitle.setTextColor(ThemeUtil.ink3(this));
        catText.setTextColor(ThemeUtil.ink3(this));
        amountText.setTextColor(ThemeUtil.ink(this));
        coordText.setBackgroundResource(
                dark ? R.drawable.bills_tag_coord_dark : R.drawable.bills_tag_coord);
        coordText.setTextColor(ContextCompat.getColor(this,
                dark ? R.color.sky : R.color.blue_deep));
        findViewById(R.id.detail_rows_top).setBackgroundColor(ThemeUtil.line(this));

        ViewGroup rows = findViewById(R.id.detail_rows);
        int rowBg = dark ? R.drawable.bills_hairline_row_dark : R.drawable.bills_hairline_row;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            row.setBackgroundResource(rowBg);
            if (!(row instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) row;
            if (group.getChildCount() < 2) continue;
            View label = group.getChildAt(0);
            View value = group.getChildAt(1);
            if (label instanceof TextView) ((TextView) label).setTextColor(ThemeUtil.ink3(this));
            if (value instanceof TextView) ((TextView) value).setTextColor(ThemeUtil.ink(this));
        }

        TextView edit = findViewById(R.id.btn_detail_edit);
        edit.setBackgroundResource(dark ? R.drawable.bills_btn_ghost_dark : R.drawable.bg_btn_ghost);
        edit.setTextColor(ThemeUtil.ink(this));
        ((TextView) findViewById(R.id.detail_same_title)).setTextColor(ThemeUtil.ink3(this));
        sameCount.setTextColor(ThemeUtil.ink(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final Handler handler = bg;
        final BillDao localDao = dao;
        if (handler == null || localDao == null || billId <= 0) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                final Bill found = localDao.get(billId);
                final List<Bill> others = new ArrayList<>();
                int sameTotal = 0;
                if (found != null) {
                    for (Bill x : localDao.listDateSlot(found.billDate, found.slot)) {
                        if (x.id == found.id || x.isIncome()) continue;
                        sameTotal++;
                        if (others.size() < 3) others.add(x);
                    }
                }
                final int total = sameTotal;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (found == null) {
                            Toast.makeText(BillDetailActivity.this,
                                    "这一笔已经不在了", Toast.LENGTH_SHORT).show();
                            finish();
                            return;
                        }
                        render(found, others, total);
                    }
                });
            }
        });
    }

    private void render(Bill b, List<Bill> same, int sameTotal) {
        bill = b;
        subtitle.setText("Entry Detail · " + DateUtil.monthDay(b.billDate));
        catText.setText(b.categoryName() + " · " + (b.isIncome() ? "收入" : "支出"));
        com.wool.ledger.util.Anim.count(amountText, 0, b.amount, "");
        coordText.setText("周" + DateUtil.weekdayCn(b.billDate) + " · " + b.slot + " " + slotHour(b.slot));
        noteText.setText(b.note == null || b.note.isEmpty() ? "—" : b.note);
        dateText.setText(DateUtil.monthDay(b.billDate) + " 周" + DateUtil.weekdayCn(b.billDate));
        timeText.setText(b.billTime);
        sourceText.setText(b.source == 3 ? "支付通知" : (b.source == 2 ? "语音录入" : (b.source == 1 ? "Agent / AI" : "手动录入")));
        createdText.setText(createdLabel(b));
        rawText.setText(b.source == 0
                ? "—"
                : (b.note == null || b.note.isEmpty() ? "—" : b.note));
        sameCount.setText(sameTotal <= 0 ? "同格就这一笔" : "同格 " + sameTotal + " 笔");

        sameList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        boolean dark = ThemeUtil.dark(this);
        int dividerColor = ContextCompat.getColor(this,
                dark ? R.color.line_dark : R.color.line2);
        for (final Bill other : same) {
            View row = inflater.inflate(R.layout.item_bill, sameList, false);
            TextView t = row.findViewById(R.id.bill_time);
            TextView name = row.findViewById(R.id.bill_name);
            TextView cat = row.findViewById(R.id.bill_cat);
            TextView amt = row.findViewById(R.id.bill_amount);
            t.setText(other.billTime);
            name.setText(other.note == null || other.note.isEmpty() ? "—" : other.note);
            cat.setText(other.categoryName() + " · " + other.slot);
            amt.setText((other.isIncome() ? "+" : "-") + Money.fmt(other.amount));
            t.setTextColor(ThemeUtil.ink3(this));
            name.setTextColor(ThemeUtil.ink(this));
            cat.setTextColor(ThemeUtil.ink3(this));
            if (other.isIncome()) {
                amt.setTextColor(ContextCompat.getColor(this,
                        dark ? R.color.bills_income_dark : R.color.bills_income));
            } else {
                amt.setTextColor(ThemeUtil.ink(this));
            }
            row.findViewById(R.id.bill_divider).setBackgroundColor(dividerColor);
            row.findViewById(R.id.bill_delete).setVisibility(View.GONE);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(BillDetailActivity.this, BillDetailActivity.class);
                    intent.putExtra("bill_id", other.id);
                    startActivity(intent);
                }
            });
            sameList.addView(row);
        }
    }

    private void edit() {
        if (bill == null) return;
        Intent intent = new Intent(this, AddRecordActivity.class);
        intent.putExtra("bill_id", bill.id);
        intent.putExtra("date", bill.billDate);
        intent.putExtra("slot", bill.slot);
        startActivityForResult(intent, REQ_EDIT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT && resultCode == RESULT_OK && data != null
                && data.getBooleanExtra("changed", false)) {
            changed = true;
            load();
        }
    }

    private void confirmDelete() {
        if (bill == null) return;
        String when = DateUtil.monthDay(bill.billDate) + " " + bill.billTime;
        new AlertDialog.Builder(this)
                .setTitle("删除这一笔？")
                .setMessage(bill.categoryName() + " ¥" + Money.fmt(bill.amount) + " · " + when
                        + "，删除后不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        delete();
                    }
                })
                .show();
    }

    private void delete() {
        final Handler handler = bg;
        final BillDao localDao = dao;
        if (handler == null || localDao == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                localDao.delete(billId);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        changed = true;
                        Toast.makeText(BillDetailActivity.this, "已删除这一笔",
                                Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
        });
    }

    private static String createdLabel(Bill b) {
        String ct = b.createTime;
        if (ct == null || ct.length() < 16) {
            ct = b.billDate + " " + b.billTime;
        }
        if (ct.length() >= 16) {
            return DateUtil.monthDay(ct.substring(0, 10)) + " " + ct.substring(11, 16);
        }
        return ct;
    }

    private static String slotHour(String slot) {
        if ("早".equals(slot)) return "07";
        if ("午".equals(slot)) return "12";
        if ("晚".equals(slot)) return "18";
        return "21";
    }

    @Override
    public void finish() {
        if (changed) {
            Intent data = new Intent();
            data.putExtra("changed", true);
            setResult(RESULT_OK, data);
        }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        if (ioThread != null) {
            ioThread.quitSafely();
            ioThread = null;
        }
        bg = null;
        super.onDestroy();
    }
}
