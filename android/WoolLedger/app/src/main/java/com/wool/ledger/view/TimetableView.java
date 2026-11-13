package com.wool.ledger.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.wool.ledger.data.Bill;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 消费课表：横轴星期、纵轴饭点，一格一笔账。
 * 交互：
 * - 点格子 → 进「这一格」
 * - 长按有账的格子 → 进入编辑态：格子右上角出现红色删除按钮，并可拖动色块到别的格子松手移动
 * - 点红色按钮 → 直接删除（不弹窗）
 */
public class TimetableView extends View {

    public interface OnCellClick {
        void onClick(String date, String slot, long billId);
    }

    public interface OnCellEdit {
        /** 整格移动：这一格里所有账单一起走 */
        void onMove(java.util.List<Long> billIds, String toDate, String toSlot);

        /** 整格删除 */
        void onDelete(java.util.List<Long> billIds);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final String[] WD = DateUtil.WD;
    private final String[] SLOTS = DateUtil.SLOTS;

    private String weekStart = DateUtil.today();
    private String today = DateUtil.today();
    private final List<Bill> bills = new ArrayList<>();
    private OnCellClick listener;
    private OnCellEdit editListener;

    private float headerH, rowH, labelW;
    private final int COLS = 7;

    // 编辑态
    private long editBillId = 0;
    private int editDay = -1, editSlot = -1;
    private boolean dragging = false;
    private boolean armed = false;
    /** 正在拖的整格账单 */
    private final java.util.List<Long> dragIds = new java.util.ArrayList<>();
    private float dragX, dragY;
    private int dropDay = -1, dropSlot = -1;
    /** 跨页拖拽（系统 drag & drop）：源格先空着，落点用同一套高亮，幽灵由系统阴影画 */
    private boolean externalDrag = false;

    private final android.os.Handler press = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pressRun;
    private float downX, downY;
    private int downDay = -1, downSlot = -1;

    public TimetableView(Context c) {
        this(c, null);
    }

    public TimetableView(Context c, AttributeSet a) {
        super(c, a);
        tp.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
    }

    public void setListener(OnCellClick l) {
        this.listener = l;
    }

    public void setOnCellEdit(OnCellEdit l) {
        this.editListener = l;
    }

    public void setData(String weekStart, List<Bill> data) {
        this.weekStart = weekStart;
        this.today = DateUtil.today();
        bills.clear();
        setContentDescription("消费课表，共 " + (data == null ? 0 : data.size()) + " 笔");
        if (data != null) bills.addAll(data);
        editBillId = 0;
        editDay = -1;
        editSlot = -1;
        dragging = false;
        invalidate();
    }

    private String dateOf(int col) {
        return DateUtil.addDays(weekStart, col);
    }

    private List<Bill> billsAt(int col, int slotIdx) {
        List<Bill> out = new ArrayList<>();
        String d = dateOf(col);
        for (Bill b : bills) {
            if (d.equals(b.billDate) && SLOTS[slotIdx].equals(b.slot)) out.add(b);
        }
        return out;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        labelW = dp(30);
        headerH = dp(26);
        rowH = dp(46);
        int h = (int) (headerH + rowH * 4 + dp(2));
        setMeasuredDimension(w, h);
    }

    private float colW() {
        return (getWidth() - labelW) / COLS;
    }

    @Override
    protected void onDraw(Canvas c) {
        float left = labelW;
        float cw = colW();
        int line = ThemeUtil.line(getContext());
        int tint = 0x142F6EA8;

        int todayIdx = -1;
        for (int i = 0; i < COLS; i++) if (dateOf(i).equals(today)) todayIdx = i;
        // 今天只做很轻的表头胶囊，不刷整列底色（整列会显得像选中状态）
        if (todayIdx >= 0) {
            p.setColor(tint);
            rect.set(left + todayIdx * cw + dp(6), dp(2),
                    left + (todayIdx + 1) * cw - dp(6), headerH - dp(2));
            c.drawRoundRect(rect, dp(6), dp(6), p);
        }

        tp.setColor(ThemeUtil.ink3(getContext()));
        tp.setTextSize(dp(10));
        tp.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < COLS; i++) {
            tp.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            c.drawText(WD[i], left + i * cw + cw / 2f, headerH * 0.62f, tp);
        }
        // 今天：列名下方一个小点
        if (todayIdx >= 0) {
            p.setColor(0xFF2F6EA8);
            c.drawCircle(left + todayIdx * cw + cw / 2f, headerH - dp(4.5f), dp(1.8f), p);
        }

        p.setColor(line);
        p.setStrokeWidth(dp(0.8f));
        for (int r = 0; r <= 4; r++) {
            float y = headerH + r * rowH;
            c.drawLine(labelW, y, getWidth(), y, p);
        }
        for (int i = 0; i <= COLS; i++) {
            float x = left + i * cw;
            c.drawLine(x, 0, x, getHeight(), p);
        }
        tp.setColor(ThemeUtil.ink3(getContext()));
        tp.setTextSize(dp(9));
        for (int s = 0; s < 4; s++) {
            c.save();
            c.rotate(-90, labelW / 2f, headerH + s * rowH + rowH / 2f);
            c.drawText(SLOTS[s] + (s == 0 ? " 07" : s == 1 ? " 12" : s == 2 ? " 18" : " 21"),
                    labelW / 2f, headerH + s * rowH + rowH / 2f + dp(3), tp);
            c.restore();
        }

        // 拖动时的目标格高亮
        if ((dragging || externalDrag) && dropDay >= 0 && dropSlot >= 0
                && (dropDay != editDay || dropSlot != editSlot)) {
            // 落点：浅色填充 + 2dp 描边，压在格子边线内侧，避免糊出格
            float pad = dp(1.5f);
            rect.set(left + dropDay * cw + pad, headerH + dropSlot * rowH + pad,
                    left + (dropDay + 1) * cw - pad, headerH + (dropSlot + 1) * rowH - pad);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1F2F6EA8);
            c.drawRoundRect(rect, dp(6), dp(6), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0xCC2F6EA8);
            c.drawRoundRect(rect, dp(6), dp(6), p);
            p.setStyle(Paint.Style.FILL);
        }

        for (int s = 0; s < 4; s++) {
            for (int d = 0; d < COLS; d++) {
                float x0 = left + d * cw;
                float y0 = headerH + s * rowH;
                float pad = dp(2.5f);
                List<Bill> list = billsAt(d, s);
                if (list.isEmpty()) {
                    drawAdd(c, x0 + pad, y0 + pad, x0 + cw - pad, y0 + rowH - pad);
                } else {
                    int n = Math.min(2, list.size());
                    float partH = (rowH - pad * 2 - (n > 1 ? dp(2) : 0)) / n;
                    for (int k = 0; k < n; k++) {
                        Bill b = list.get(k);
                        // 拖动中：这一格的所有账单都先藏起来（原位置不留残影），松手后在落点重画
                        if ((dragging || armedBill || externalDrag) && dragIds.contains(b.id)) continue;
                        float top = y0 + pad + k * (partH + dp(2));
                        drawBlock(c, x0 + pad, top, x0 + cw - pad, top + partH, b);
                    }
                }
            }
        }

        // 编辑态的删除按钮（格子右上角红色圆形）
        if (editBillId > 0 && editDay >= 0 && editSlot >= 0) {
            float bx = left + (editDay + 1) * cw - dp(4);
            float by = headerH + editSlot * rowH + dp(9);
            if (!dragging) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFD9453B);
                c.drawCircle(bx, by, dp(8), p);
                p.setColor(Color.WHITE);
                p.setStrokeWidth(dp(1.6f));
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(bx - dp(3), by - dp(3), bx + dp(3), by + dp(3), p);
                c.drawLine(bx + dp(3), by - dp(3), bx - dp(3), by + dp(3), p);
                p.setStyle(Paint.Style.FILL);
            }
        }

        // 被拖动的块：整格一起跟手，尺寸和"格子里原样"一致
        // （一个格子的高度里上下分块，而不是两块整格堆起来显得像两格）
        if (dragging || armedBill) {
            java.util.List<Bill> moving = new java.util.ArrayList<>();
            for (Bill b : bills) if (dragIds.contains(b.id)) moving.add(b);
            if (!moving.isEmpty()) {
                int n = Math.min(2, moving.size());
                float pad = dp(2.5f);
                float w = cw - pad * 2;
                float cellH = rowH - pad * 2;
                float gap = dp(2);
                float partH = (cellH - (n - 1) * gap) / n;
                float totalH = n * partH + (n - 1) * gap;
                float top = dragY - totalH / 2f;
                for (int k = 0; k < n; k++) {
                    Bill b = moving.get(k);
                    float y0 = top + k * (partH + gap);
                    p.setColor(b.categoryColor());
                    p.setAlpha(235);
                    rect.set(dragX - w / 2, y0, dragX + w / 2, y0 + partH);
                    c.drawRoundRect(rect, dp(4), dp(4), p);
                    p.setAlpha(255);
                    tp.setColor(Color.WHITE);
                    tp.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                    tp.setTextSize(dp(9.5f));
                    tp.setTextAlign(Paint.Align.CENTER);
                    c.drawText((b.isIncome() ? "+" : "-") + Money.shortFmt(b.amount),
                            dragX, y0 + partH / 2f + dp(3.4f), tp);
                }
            }
        }
    }

    private Bill findById(long id) {
        for (Bill b : bills) if (b.id == id) return b;
        return null;
    }

    private void drawAdd(Canvas c, float l, float t, float r, float b) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(0.9f));
        p.setColor(0x339A9F98);
        p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3), dp(3)}, 0));
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, dp(5), dp(5), p);
        p.setPathEffect(null);

        tp.setColor(0x669AA0A6);
        tp.setTextSize(dp(12));
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText("+", (l + r) / 2f, (t + b) / 2f + dp(4), tp);
    }

    private void drawBlock(Canvas c, float l, float t, float r, float b, Bill bill) {
        int color = bill.categoryColor();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        rect.set(l, t, r, b);
        float rad = dp(4);
        c.drawRoundRect(rect, rad, rad, p);

        tp.setColor(Color.WHITE);
        tp.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        tp.setTextSize(dp(9.5f));
        tp.setTextAlign(Paint.Align.CENTER);
        String txt = (bill.isIncome() ? "+" : "-") + Money.shortFmt(bill.amount);
        c.drawText(txt, (l + r) / 2f, (t + b) / 2f + dp(3.4f), tp);
    }

    /** 屏幕坐标 → 日期（给「这一格」里还原的课表快照用） */
    public String dateAtScreen(float sx, float sy) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int d = dayAt(sx - loc[0]);
        return d >= 0 ? dateOf(d) : null;
    }

    /** 屏幕坐标 → 时段 */
    public String slotAtScreen(float sx, float sy) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int s = slotAt(sy - loc[1]);
        return s >= 0 ? SLOTS[s] : null;
    }

    /** 某一格的屏幕矩形（给拖拽高亮用） */
    public android.graphics.Rect cellRectOnScreen(String date, String slot) {
        int d = -1, s = -1;
        for (int i = 0; i < COLS; i++) if (dateOf(i).equals(date)) d = i;
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i].equals(slot)) s = i;
        if (d < 0 || s < 0) return null;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        float cw = colW();
        float pad = dp(2.5f);
        int l = (int) (loc[0] + labelW + d * cw + pad);
        int t = (int) (loc[1] + headerH + s * rowH + pad);
        int r = (int) (loc[0] + labelW + (d + 1) * cw - pad);
        int b = (int) (loc[1] + headerH + (s + 1) * rowH - pad);
        return new android.graphics.Rect(l, t, r, b);
    }

    private int dayAt(float x) {
        int d = (int) ((x - labelW) / colW());
        return (d >= 0 && d < COLS) ? d : -1;
    }

    private int slotAt(float y) {
        int s = (int) ((y - headerH) / rowH);
        return (s >= 0 && s < 4) ? s : -1;
    }

    private boolean onDeleteButton(float x, float y) {
        if (editBillId <= 0 || editDay < 0 || editSlot < 0) return false;
        float cw = colW();
        float bx = labelW + (editDay + 1) * cw - dp(4);
        float by = headerH + editSlot * rowH + dp(9);
        return Math.hypot(x - bx, y - by) <= dp(11);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();

        // 外部拖拽模式：手指在哪里，幽灵块就跟到哪里，松手落位
        if (armedBill) {
            int d = dayAt(x);
            int s = slotAt(y);
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                dragging = true;
                dragX = x;
                dragY = y;
                dropDay = d;
                dropSlot = s;
                invalidate();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                dragX = x;
                dragY = y;
                dropDay = d;
                dropSlot = s;
                invalidate();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                boolean ok = d >= 0 && s >= 0;
                armedBill = false;
                dragging = false;
                java.util.List<Long> ids = new java.util.ArrayList<>(dragIds);
                String toDate = ok ? dateOf(d) : null;
                String toSlot = ok ? SLOTS[s] : null;
                if (editListener != null && ok) editListener.onMove(ids, toDate, toSlot);
                exitEdit();
                return true;
            }
        }

        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            // 课表在 NestedScrollView 里，不禁止父级拦截的话，一竖着拖就被列表滚走（表现为"不跟手"）
            android.view.ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            downX = x;
            downY = y;
            downDay = dayAt(x);
            downSlot = slotAt(y);
            if (editBillId > 0) {
                // 编辑态下的点击：删除按钮 / 拖动开始 / 退出编辑态
                if (onDeleteButton(x, y)) {
                    java.util.List<Long> ids = new java.util.ArrayList<>(dragIds);
                    exitEdit();
                    if (editListener != null) editListener.onDelete(ids);
                    return true;
                }
                if (downDay >= 0 && downSlot >= 0) return true;
                exitEdit();
                return true;
            }
            if (downDay >= 0 && downSlot >= 0) {
                final int d = downDay, s = downSlot;
                pressRun = () -> {
                    List<Bill> list = billsAt(d, s);
                    if (!list.isEmpty()) {
                        dragIds.clear();
                        for (Bill b : list) dragIds.add(b.id);
                        editBillId = list.get(0).id;   // 仅用于绘制
                        editDay = d;
                        editSlot = s;
                        dragX = x;
                        dragY = y;
                        armed = true;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        invalidate();
                    }
                };
                press.postDelayed(pressRun, 420);
            }
            return true;
        }

        if (e.getAction() == MotionEvent.ACTION_MOVE) {
            if (editBillId > 0) {
                if (!dragging && Math.abs(x - downX) < dp(8) && Math.abs(y - downY) < dp(8)) {
                    return true;
                }
                dragging = true;
                dragX = x;
                dragY = y;
                dropDay = dayAt(x);
                dropSlot = slotAt(y);
                invalidate();
                return true;
            }
            if (pressRun != null && (Math.abs(x - downX) > dp(8) || Math.abs(y - downY) > dp(8))) {
                press.removeCallbacks(pressRun);
                pressRun = null;
            }
            return true;
        }

        if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
            android.view.ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            if (pressRun != null) {
                press.removeCallbacks(pressRun);
                pressRun = null;
            }
            if (editBillId > 0) {
                if (armed && !dragging) {
                    armed = false;
                    return true;    // 长按松手：保持编辑态，等着点红叉或拖拽
                }
                if (dragging) {
                    boolean inGrid = dropDay >= 0 && dropSlot >= 0;
                    boolean moved = inGrid && (dropDay != editDay || dropSlot != editSlot);
                    java.util.List<Long> ids = new java.util.ArrayList<>(dragIds);
                    String toDate = dropDay >= 0 ? dateOf(dropDay) : null;
                    String toSlot = dropSlot >= 0 ? SLOTS[dropSlot] : null;
                    exitEdit();
                    if (moved && editListener != null) editListener.onMove(ids, toDate, toSlot);
                } else {
                    exitEdit();
                }
                return true;
            }
            int d = dayAt(x), s = slotAt(y);
            if (e.getAction() == MotionEvent.ACTION_UP && d >= 0 && s >= 0 && d == downDay && s == downSlot) {
                List<Bill> list = billsAt(d, s);
                long id = 0;
                if (!list.isEmpty()) {
                    float inCell = (y - headerH - s * rowH) / rowH;
                    id = list.get(list.size() > 1 && inCell > 0.5f ? 1 : 0).id;
                }
                if (listener != null) listener.onClick(dateOf(d), SLOTS[s], id);
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    /** 从外部带着一笔账单进来：等用户在课表上拖到目标格 */
    /* ---------- 跨页拖拽（这一格 → 课表，走系统拖放） ---------- */

    /** 长按从别的页面拖过来时：先把这一格的账单从画布上隐藏，进入"等待落点"状态 */
    public void beginExternalDrag(java.util.List<Long> ids) {
        dragIds.clear();
        if (ids != null) dragIds.addAll(ids);
        externalDrag = true;
        dragging = false;
        armedBill = false;
        armed = false;
        editBillId = 0;
        editDay = -1;
        editSlot = -1;
        dropDay = -1;
        dropSlot = -1;
        invalidate();
    }

    /** 手指在格子上移动：更新落点高亮 */
    public void updateDropTarget(float x, float y) {
        int d = dayAt(x);
        int s = slotAt(y);
        if (d != dropDay || s != dropSlot) {
            dropDay = d;
            dropSlot = s;
            invalidate();
        }
    }

    public String dropDateAt(float x) {
        int d = dayAt(x);
        return d >= 0 ? dateOf(d) : null;
    }

    public String dropSlotAt(float y) {
        int s = slotAt(y);
        return s >= 0 ? SLOTS[s] : null;
    }

    /** 拖拽结束（不管落没落进格子）都要收尾 */
    public void endExternalDrag() {
        externalDrag = false;
        dragIds.clear();
        dropDay = -1;
        dropSlot = -1;
        dragging = false;
        invalidate();
    }

    public void armDrag(long billId) {
        exitEdit();
        dragIds.clear();
        dragIds.add(billId);
        editBillId = billId;
        armedBill = true;
        invalidate();
    }

    private boolean armedBill = false;

    private void exitEdit() {
        armed = false;
        dragIds.clear();
        editBillId = 0;
        editDay = -1;
        editSlot = -1;
        dragging = false;
        dropDay = -1;
        dropSlot = -1;
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
