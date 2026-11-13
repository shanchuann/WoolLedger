package com.wool.ledger.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.wool.ledger.data.Category;
import com.wool.ledger.util.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日支出堆叠柱：一根柱子按分类颜色从下往上堆，柱顶标当日合计。
 * 切换时间范围时用 ValueAnimator 做高度补间（0 → 1）。
 */
public class StackedBarView extends View {

    public interface OnDayClick {
        void onClick(String date);
    }

    private OnDayClick dayClick;
    private java.util.List<String> dayKeys = new java.util.ArrayList<>();

    public java.util.List<String> dayKeys() {
        return dayKeys;
    }

    public void setDayKeys(java.util.List<String> keys) {
        dayKeys = keys == null ? new java.util.ArrayList<>() : keys;
    }

    public void setOnDayClick(OnDayClick l) {
        this.dayClick = l;
    }

    /** 一根柱子：日期标签 + 分类金额（保持插入顺序，颜色取 Category.color）。 */
    public static class Bucket {
        public String label = "";
        public final LinkedHashMap<String, Double> segments = new LinkedHashMap<>();
        public double total;

        public Bucket() {
        }

        public Bucket(String label) {
            this.label = label;
        }

        public void add(String code, double amount) {
            if (amount <= 0) return;
            Double old = segments.get(code);
            segments.put(code, old == null ? amount : old + amount);
            total += amount;
        }
    }

    private final List<Bucket> buckets = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float progress = 1f;
    private ValueAnimator animator;
    private boolean dark;

    private final float gap;
    private final float minBar;
    private final float maxBar;
    private final float padSide;
    private final float topPad;
    private final float bottomPad;
    private final float hair;

    public StackedBarView(Context c) {
        this(c, null);
    }

    public StackedBarView(Context c, AttributeSet attrs) {
        super(c, attrs);
        float d = c.getResources().getDisplayMetrics().density;
        gap = 8 * d;
        minBar = 8 * d;
        maxBar = 26 * d;
        padSide = 6 * d;
        topPad = 20 * d;
        bottomPad = 20 * d;
        hair = Math.max(1f, d);

        basePaint.setColor(0xFFDFE2DE);
        basePaint.setStrokeWidth(hair);

        labelPaint.setTextSize(sp(9));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(sp(8));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        paint.setStyle(Paint.Style.FILL);
        applyTheme();
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    public void setDark(boolean dark) {
        this.dark = dark;
        applyTheme();
        invalidate();
    }

    private void applyTheme() {
        basePaint.setColor(dark ? 0xFF2A323B : 0xFFDFE2DE);
        labelPaint.setColor(dark ? 0xFF8F9AA6 : 0xFF6B7076);
        valuePaint.setColor(dark ? 0xFF8F9AA6 : 0xFF6B7076);
    }

    public void setData(List<Bucket> data, boolean animate) {
        buckets.clear();
        if (data != null) buckets.addAll(data);
        if (animator != null) animator.cancel();
        if (!animate) {
            progress = 1f;
            invalidate();
            return;
        }
        progress = 0f;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(480);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (e.getAction() == android.view.MotionEvent.ACTION_UP && dayKeys.size() > 0) {
            float w = getWidth(), slot = w / dayKeys.size();
            int i = (int) (e.getX() / slot);
            if (i >= 0 && i < dayKeys.size() && dayClick != null) dayClick.onClick(dayKeys.get(i));
            performClick();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float baseY = h - bottomPad;
        final float areaTop = topPad;
        final float usable = Math.max(1f, w - padSide * 2);
        final int n = buckets.size();
        if (n == 0) return;
        float gapNow = n > 8 ? gap * 0.55f : gap;
        float barW = Math.min(maxBar, (usable - gapNow * (n - 1)) / n);
        if (barW < minBar) {
            barW = minBar;
            gapNow = Math.max(3f, (usable - barW * n) / Math.max(1, n - 1));
        }
        final float totalW = barW * n + gapNow * (n - 1);
        final float x0 = (w - totalW) / 2f;

        double max = 0;
        for (Bucket b : buckets) max = Math.max(max, b.total);
        if (max <= 0) max = 1;

        for (int i = 0; i < n; i++) {
            Bucket b = buckets.get(i);
            float x = x0 + i * (barW + gapNow);
            float y = baseY;
            float avail = baseY - areaTop;
            for (Map.Entry<String, Double> e : b.segments.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                float segH = (float) (e.getValue() / max) * avail * progress;
                if (segH < 0.8f) segH = 0.8f;
                float bottom = y;
                y -= segH;
                paint.setColor(Category.byCode(e.getKey()).color);
                rect.set(x, y, x + barW, bottom);
                canvas.drawRect(rect, paint);
            }
            if (b.total > 0 && b.segments.isEmpty()) {
                float segH = (float) (b.total / max) * avail * progress;
                paint.setColor(0xFFB9BDBA);
                canvas.drawRect(x, baseY - segH, x + barW, baseY, paint);
            }
            // 当日合计（柱顶）
            if (b.total > 0 && (barW >= 17f * getResources().getDisplayMetrics().density || n <= 7)) {
                String amount = "\u00a5" + Money.shortFmt(b.total);
                canvas.drawText(amount, x + barW / 2f, Math.max(areaTop - 5f, y - 4f), valuePaint);
            }
            // 日期
            canvas.drawText(b.label == null ? "" : b.label, x + barW / 2f, baseY + 13f, labelPaint);
        }

        basePaint.setColor(dark ? 0xFF2A323B : 0xFFDFE2DE);
        canvas.drawLine(padSide, baseY, w - padSide, baseY, basePaint);
    }
}
