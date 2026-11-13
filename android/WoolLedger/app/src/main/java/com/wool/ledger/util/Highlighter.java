package com.wool.ledger.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** 荧光笔：只在文字下沿画一条带，不做整块底色。 */
public class Highlighter extends Drawable {

    private final Drawable inner;
    private final float bandHeight;

    public Highlighter(Drawable inner, float bandHeight) {
        this.inner = inner;
        this.bandHeight = bandHeight;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int h = (int) Math.min(bandHeight, b.height() * 0.32f);
        inner.setBounds(b.left + (int) (bandHeight * 0.2f), b.bottom - h,
                b.right - (int) (bandHeight * 0.2f), b.bottom);
        inner.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        inner.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        inner.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
