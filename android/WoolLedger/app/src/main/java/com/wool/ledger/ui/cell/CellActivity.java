package com.wool.ledger.ui.cell;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
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
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 这一格：某天某时段（早 / 午 / 晚 / 夜）的合计、笔数与逐笔明细。
 * 行尾 × 删除（二次确认），行点击进「记一笔」编辑，底部「再记一笔」带坐标新建。
 */
public class CellActivity extends AppCompatActivity {

    private static final int REQ_EDIT = 2001;

    private HandlerThread ioThread;
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BillDao dao;
    private String date;
    private String slot;
    private boolean changed;

    private TextView subtitle;
    private TextView totalText;
    private TextView countText;
    private TextView emptyText;
    private LinearLayout listContainer;

    /** 长按拖动时用来「还原课表」的全屏快照层 */
    private android.widget.FrameLayout dragPage;
    private android.view.View dragHighlight;
    private long dragBillId;
    private String dragBillDate;
    private String dragBillSlot;
    private boolean dragDropped;
    private float dragStartX, dragStartY;
    private boolean dragMoved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.applyTheme(this);
        setContentView(R.layout.activity_cell);

        date = getIntent().getStringExtra("date");
        if (date == null || date.length() < 10) date = DateUtil.today();
        slot = getIntent().getStringExtra("slot");
        if (slot == null || slot.length() == 0) slot = Prefs.defSlot(this);

        subtitle = findViewById(R.id.cell_sub);
        totalText = findViewById(R.id.cell_total);
        countText = findViewById(R.id.cell_count);
        emptyText = findViewById(R.id.cell_empty);
        listContainer = findViewById(R.id.cell_list);

        subtitle.setText("Cell · 周" + DateUtil.weekdayCn(date) + " " + slot + " " + slotHour(slot));

        findViewById(R.id.btn_cell_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_cell_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAddRecord(0L);
            }
        });

        ioThread = new HandlerThread("cell-io");
        ioThread.start();
        bg = new Handler(ioThread.getLooper());
        dao = new BillDao(this);

        applyThemeColors();
    }

    /** 深色（Prefs.dark）统一从 ThemeUtil 取色，浅色时与原版一致 */
    private void applyThemeColors() {
        boolean dark = ThemeUtil.dark(this);
        findViewById(R.id.cell_root).setBackgroundColor(ThemeUtil.paper(this));
        ((TextView) findViewById(R.id.cell_title)).setTextColor(ThemeUtil.ink(this));
        subtitle.setTextColor(ThemeUtil.ink3(this));
        ((TextView) findViewById(R.id.cell_label)).setTextColor(ThemeUtil.ink3(this));
        totalText.setTextColor(ThemeUtil.ink(this));
        countText.setTextColor(ThemeUtil.ink3(this));
        findViewById(R.id.cell_divider).setBackgroundColor(ThemeUtil.line(this));
        emptyText.setTextColor(ThemeUtil.ink3(this));
        TextView add = findViewById(R.id.btn_cell_add);
        add.setBackgroundResource(dark ? R.drawable.bills_btn_primary_dark : R.drawable.bg_btn_primary);
        add.setTextColor(dark
                ? ContextCompat.getColor(this, R.color.bills_on_primary_dark) : 0xFFFFFFFF);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final Handler handler = bg;
        final BillDao localDao = dao;
        final String day = date;
        final String slotName = slot;
        if (handler == null || localDao == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                List<Bill> rows = new ArrayList<>();
                double expense = 0, income = 0;
                for (Bill b : localDao.listDateSlot(day, slotName)) {
                    // 收入、支出分开合计，不能把 +500 和 -1 加成一个数
                    rows.add(b);
                    if (b.isIncome()) income += b.amount;
                    else expense += b.amount;
                }
                final List<Bill> result = rows;
                final double exp = expense, inc = income;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        render(result, exp, inc);
                    }
                });
            }
        });
    }

    private void render(List<Bill> rows, double expense, double income) {
        if (rows.isEmpty()) {
            totalText.setText("¥0.00");
        } else if (expense > 0) {
            totalText.setText("支出 ¥" + Money.fmt(expense));
        } else {
            totalText.setText("收入 ¥" + Money.fmt(income));
        }
        String count = rows.size() + " 笔";
        if (expense > 0 && income > 0) count += " · 收入 ¥" + Money.fmt(income);
        countText.setText(count);
        boolean empty = rows.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);

        listContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        boolean dark = ThemeUtil.dark(this);
        int dividerColor = ContextCompat.getColor(this,
                dark ? R.color.line_dark : R.color.line2);
        for (final Bill b : rows) {
            View row = inflater.inflate(R.layout.item_bill, listContainer, false);
            TextView time = row.findViewById(R.id.bill_time);
            TextView name = row.findViewById(R.id.bill_name);
            TextView cat = row.findViewById(R.id.bill_cat);
            TextView amount = row.findViewById(R.id.bill_amount);
            TextView delete = row.findViewById(R.id.bill_delete);

            time.setText(b.billTime);
            name.setText(b.note == null || b.note.isEmpty() ? "—" : b.note);
            cat.setText(b.categoryName() + " · " + b.slot);
            amount.setText((b.isIncome() ? "+" : "-") + Money.fmt(b.amount));
            time.setTextColor(ThemeUtil.ink3(this));
            name.setTextColor(ThemeUtil.ink(this));
            cat.setTextColor(ThemeUtil.ink3(this));
            amount.setTextColor(b.isIncome()
                    ? ContextCompat.getColor(this, dark ? R.color.bills_income_dark : R.color.bills_income)
                    : ThemeUtil.ink(this));
            row.findViewById(R.id.bill_divider).setBackgroundColor(dividerColor);
            delete.setTextColor(ContextCompat.getColor(this,
                    dark ? R.color.ink3_dark : R.color.ink4));

            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(b);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openAddRecord(b.id);
                }
            });
            // 按下缩小一点，抬起还原（点按反馈）
            row.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        break;
                    default:
                        break;
                }
                return false;
            });
            // 长按：进入单笔拖动。用系统 drag & drop，手指不用抬起，
            // 回课表页后仍是同一条手势，拖到目标格松手即可（以前靠"回页面再点一下"续拖，会卡）
            row.setOnLongClickListener(v -> {
                com.wool.ledger.util.Haptic.longPress(v);
                // 兜底：万一进这一格时课表快照没抓到（窗口未量好/OOM），不要启动一个
                // 没有落点映射的拖拽，否则会卡在本页；直接按老行为回课表。
                if (com.wool.ledger.util.DragBus.snapshot == null
                        || com.wool.ledger.util.DragBus.snapshot.isRecycled()
                        || com.wool.ledger.util.DragBus.table() == null) {
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                    v.postDelayed(() -> {
                        changed = true;
                        setResult(RESULT_OK, new android.content.Intent().putExtra("changed", true));
                        finish();
                    }, 90);
                    return true;
                }
                com.wool.ledger.util.DragBus.put(b.id, date, slot);
                dragBillId = b.id;
                dragBillDate = b.billDate;
                dragBillSlot = b.slot;
                dragDropped = false;
                // 系统 drag 会话绑在本窗口上，直接 finish 回课表会让拖拽立刻结束。
                // 这里不 finish：在本页盖一张课表快照 + 目标格高亮，同一条手势拖到目标格松手后落库再 finish。
                showTimetableDragOverlay();
                boolean started = startBillDrag(v, b);
                v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.85f).setDuration(120).start();
                if (!started) {
                    hideTimetableDragOverlay();
                    com.wool.ledger.util.DragBus.clear();
                    v.postDelayed(() -> {
                        changed = true;
                        setResult(RESULT_OK, new android.content.Intent().putExtra("changed", true));
                        finish();
                    }, 160);
                }
                return true;
            });
            listContainer.addView(row);
        }
    }

    /** 启动系统拖放；阴影画成课表格子的样式（圆角色块 + 金额） */
    private boolean startBillDrag(View v, Bill b) {
        try {
            float d = getResources().getDisplayMetrics().density;
            int screenW = getResources().getDisplayMetrics().widthPixels;
            // 跟课表里"一格"的色块尺寸完全一致：格宽 - 左右各 2.5dp，行高 - 上下各 2.5dp
            int cellW = (int) ((screenW - 30 * d) / 7);
            int pad = Math.round(2.5f * d);
            int shadowW = cellW - pad * 2;
            int shadowH = Math.round(46 * d) - pad * 2;
            android.content.ClipData data = android.content.ClipData.newPlainText("wool-bill", String.valueOf(b.id));
            return v.startDragAndDrop(data, new CellShadow(b, shadowW, shadowH, d), (Long) b.id, 0);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- 课表快照上的跨页拖放 ---------------- */

    private int dpx(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void showTimetableDragOverlay() {
        if (dragPage != null) return;
        android.graphics.Bitmap snap = com.wool.ledger.util.DragBus.snapshot;
        if (snap == null || snap.isRecycled()) return;
        android.view.ViewGroup host = (android.view.ViewGroup) getWindow().getDecorView();
        android.widget.FrameLayout page = new android.widget.FrameLayout(this);
        page.setBackgroundColor(com.wool.ledger.util.ThemeUtil.paper(this));

        android.widget.ImageView img = new android.widget.ImageView(this);
        img.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        img.setImageBitmap(snap);
        page.addView(img, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        android.view.View scrim = new android.view.View(this);
        scrim.setBackgroundColor(0x22000000);
        page.addView(scrim, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("拖到课表格子松手");
        hint.setTextSize(12);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xCC12171E);
        pill.setCornerRadius(dpx(18));
        hint.setBackground(pill);
        hint.setPadding(dpx(14), dpx(8), dpx(14), dpx(8));
        android.widget.FrameLayout.LayoutParams hlp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dpx(96);
        page.addView(hint, hlp);

        android.view.View hl = new android.view.View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x332F6EA8);
        g.setCornerRadius(dpx(6));
        g.setStroke(Math.max(1, dpx(2)), 0xCC2F6EA8);
        hl.setBackground(g);
        hl.setVisibility(android.view.View.GONE);
        page.addView(hl, new android.widget.FrameLayout.LayoutParams(0, 0));

        page.setOnDragListener(this::onTimetableDrag);
        host.addView(page, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        dragPage = page;
        dragHighlight = hl;
    }

    private void hideTimetableDragOverlay() {
        if (dragPage == null) return;
        android.view.ViewParent p = dragPage.getParent();
        if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(dragPage);
        dragPage = null;
        dragHighlight = null;
    }

    private boolean onTimetableDrag(android.view.View v, android.view.DragEvent e) {
        switch (e.getAction()) {
            case android.view.DragEvent.ACTION_DRAG_STARTED:
                dragStartX = e.getX();
                dragStartY = e.getY();
                dragMoved = false;
                return e.getClipDescription() != null
                        && e.getClipDescription().hasMimeType(
                        android.content.ClipDescription.MIMETYPE_TEXT_PLAIN);
            case android.view.DragEvent.ACTION_DRAG_LOCATION:
                if (Math.hypot(e.getX() - dragStartX, e.getY() - dragStartY) > dpx(24)) {
                    dragMoved = true;
                }
                updateTimetableDragHighlight(e.getX(), e.getY());
                return true;
            case android.view.DragEvent.ACTION_DRAG_EXITED:
                if (dragHighlight != null) dragHighlight.setVisibility(android.view.View.GONE);
                return true;
            case android.view.DragEvent.ACTION_DROP: {
                // 只是长按没拖动：不要落库/不要退出，避免误把账单挪走
                if (!dragMoved) {
                    hideTimetableDragOverlay();
                    com.wool.ledger.util.DragBus.clear();
                    android.widget.Toast.makeText(this, "按住拖到目标格再松手",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                com.wool.ledger.view.TimetableView t = com.wool.ledger.util.DragBus.table();
                String toDate = t == null ? null : t.dateAtScreen(e.getX(), e.getY());
                String toSlot = t == null ? null : t.slotAtScreen(e.getX(), e.getY());
                if (toDate != null && toSlot != null) {
                    dropDraggedBill(toDate, toSlot);
                } else {
                    hideTimetableDragOverlay();
                    com.wool.ledger.util.DragBus.clear();
                }
                return true;
            }
            case android.view.DragEvent.ACTION_DRAG_ENDED:
                if (!dragDropped) {
                    hideTimetableDragOverlay();
                    com.wool.ledger.util.DragBus.clear();
                }
                return true;
            default:
                return true;
        }
    }

    private void updateTimetableDragHighlight(float sx, float sy) {
        if (dragHighlight == null) return;
        com.wool.ledger.view.TimetableView t = com.wool.ledger.util.DragBus.table();
        if (t == null) {
            dragHighlight.setVisibility(android.view.View.GONE);
            return;
        }
        String d = t.dateAtScreen(sx, sy);
        String s = t.slotAtScreen(sx, sy);
        android.graphics.Rect r = (d == null || s == null) ? null : t.cellRectOnScreen(d, s);
        if (r == null) {
            dragHighlight.setVisibility(android.view.View.GONE);
            return;
        }
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) dragHighlight.getLayoutParams();
        lp.width = r.width();
        lp.height = r.height();
        lp.leftMargin = r.left;
        lp.topMargin = r.top;
        dragHighlight.setLayoutParams(lp);
        dragHighlight.setVisibility(android.view.View.VISIBLE);
    }

    private void dropDraggedBill(final String toDate, final String toSlot) {
        if (dragPage == null) return;
        dragDropped = true;
        hideTimetableDragOverlay();
        final boolean same = toDate.equals(dragBillDate) && toSlot.equals(dragBillSlot);
        com.wool.ledger.util.DragBus.clear();
        if (same) {
            finish();
            return;
        }
        com.wool.ledger.util.Haptic.confirm(getWindow().getDecorView());
        new Thread(() -> {
            Bill b = dao.get(dragBillId);
            if (b != null) {
                b.billDate = toDate;
                b.slot = toSlot;
                dao.update(b);
            }
            main.post(() -> {
                changed = true;
                android.widget.Toast.makeText(this,
                        "已移动 1 笔至 " + DateUtil.shortDate(toDate) + " · " + toSlot,
                        android.widget.Toast.LENGTH_SHORT).show();
                finish();
            });
        }).start();
    }

    /** 跟手阴影：一个课表格子大小的色块，手指在中心 */
    private static class CellShadow extends View.DragShadowBuilder {
        private final Bill bill;
        private final int w, h;
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        private final float density;

        CellShadow(Bill b, int w, int h, float density) {
            super();
            this.bill = b;
            this.w = w;
            this.h = h;
            this.density = density;
        }

        @Override
        public void onProvideShadowMetrics(android.graphics.Point size, android.graphics.Point touch) {
            size.set(w, h);
            touch.set(w / 2, h / 2);
        }

        @Override
        public void onDrawShadow(android.graphics.Canvas canvas) {
            if (bill == null) return;
            fill.setColor(bill.categoryColor());
            fill.setAlpha(238);
            float r = 4 * density;                 // 和课表里的色块同圆角
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, fill);
            text.setColor(android.graphics.Color.WHITE);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            text.setTextSize(9.5f * density);       // 课表里金额是 9.5sp
            text.setTextAlign(android.graphics.Paint.Align.CENTER);
            String s = (bill.isIncome() ? "+" : "-") + Money.shortFmt(bill.amount);
            canvas.drawText(s, w / 2f, h / 2f + text.getTextSize() * 0.35f, text);
        }
    }

    private void confirmDelete(final Bill bill) {
        String when = DateUtil.shortDate(bill.billDate) + " " + bill.slot + " " + bill.billTime;
        new AlertDialog.Builder(this)
                .setTitle("删除这一笔？")
                .setMessage(bill.categoryName() + " ¥" + Money.fmt(bill.amount) + " · " + when
                        + "，删除后不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteBill(bill);
                    }
                })
                .show();
    }

    private void deleteBill(final Bill bill) {
        final Handler handler = bg;
        final BillDao localDao = dao;
        if (handler == null || localDao == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                localDao.delete(bill.id);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        changed = true;
                        Toast.makeText(CellActivity.this, "已删除这一笔", Toast.LENGTH_SHORT).show();
                        load();
                    }
                });
            }
        });
    }

    private void openAddRecord(long billId) {
        Intent intent = new Intent(this, AddRecordActivity.class);
        intent.putExtra("bill_id", billId);
        intent.putExtra("date", date);
        intent.putExtra("slot", slot);
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
        hideTimetableDragOverlay();
        com.wool.ledger.util.DragBus.clear();
        com.wool.ledger.util.DragBus.clearSnapshot();
        if (ioThread != null) {
            ioThread.quitSafely();
            ioThread = null;
        }
        bg = null;
        super.onDestroy();
    }

}