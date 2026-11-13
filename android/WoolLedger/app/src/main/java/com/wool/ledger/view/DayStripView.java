package com.wool.ledger.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import com.wool.ledger.util.Money;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日期圆点条：最近 7 天，圆点里是日号，下面是当天支出。
 * 选中 = 墨底白字 + 荧光笔黄描边（再点一次取消单日筛选）。
 */
public class DayStripView extends View {

    public interface OnDaySelected {
        void onDaySelected(String date);
    }

    private final List<String> dates = new ArrayList<>();
    private final Map<String, Double> sums = new HashMap<>();
    private String selected;
    private String today;
    private OnDaySelected listener;
    private boolean dark;

    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint amtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public DayStripView(Context c) {
        this(c, null);
    }

    public DayStripView(Context c, AttributeSet attrs) {
        super(c, attrs);
        density = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * density);
        ring.setColor(0xFFF5E663);
        tint.setStyle(Paint.Style.FILL);
        dayPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        dayPaint.setTextAlign(Paint.Align.CENTER);
        dayPaint.setTextSize(sp(13));
        amtPaint.setTextAlign(Paint.Align.CENTER);
        amtPaint.setTextSize(sp(9));
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
        circle.setColor(dark ? 0xFFF3EFE4 : 0xFF1A1F24);
        tint.setColor(dark ? 0x14FFFFFF : 0x0E1A1F24);
        amtPaint.setColor(dark ? 0xFF8F9AA6 : 0xFF6B7076);
    }

    public void setDays(List<String> dates, Map<String, Double> sums, String selected, String today) {
        this.dates.clear();
        if (dates != null) this.dates.addAll(dates);
        this.sums.clear();
        if (sums != null) this.sums.putAll(sums);
        this.selected = selected;
        this.today = today;
        invalidate();
    }

    public void setOnDaySelected(OnDaySelected l) {
        this.listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (72 * density);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        if (hMode == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int n = dates.size();
        if (n == 0) return;
        final float w = getWidth();
        final float itemW = w / (float) n;
        final float centerY = 4 * density + 19 * density;
        final float radius = Math.min(19 * density, itemW / 2f - 4 * density);

        for (int i = 0; i < n; i++) {
            String date = dates.get(i);
            float cx = itemW * i + itemW / 2f;
            boolean isSel = date.equals(selected);
            boolean isToday = date.equals(today);
            if (isSel || isToday) {
                canvas.drawCircle(cx, centerY, radius, circle);
                dayPaint.setColor(dark ? 0xFF15181C : 0xFFFAFAF7);
            } else {
                canvas.drawCircle(cx, centerY, radius, tint);
                dayPaint.setColor(dark ? 0xFFB7B2A5 : 0xFF5C6166);
            }
            if (isSel) canvas.drawCircle(cx, centerY, radius + 2.5f * density, ring);
            String num = date.length() >= 10 ? String.valueOf(Integer.parseInt(date.substring(8, 10))) : "?";
            float baseline = centerY - (dayPaint.descent() + dayPaint.ascent()) / 2f;
            canvas.drawText(num, cx, baseline, dayPaint);

            Double sum = sums.get(date);
            String amount = sum == null || sum <= 0 ? "\u2014" : "\u00a5" + Money.shortFmt(sum);
            amtPaint.setColor(isToday || isSel
                    ? (dark ? 0xFFF3EFE4 : 0xFF1A1F24)
                    : (dark ? 0xFF8F9AA6 : 0xFF6B7076));
            canvas.drawText(amount, cx, centerY + radius + 15 * density, amtPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int n = dates.size();
            if (n > 0 && e.getX() >= 0 && e.getX() <= getWidth()) {
                int idx = (int) (e.getX() / (getWidth() / (float) n));
                if (idx >= 0 && idx < n) {
                    if (listener != null) listener.onDaySelected(dates.get(idx));
                    performClick();
                    return true;
                }
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
