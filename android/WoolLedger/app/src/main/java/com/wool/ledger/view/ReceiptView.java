package com.wool.ledger.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 Canvas 画一张热敏小票：纸色 #FFFCF3、等宽金额、虚线分隔、条码、蓝色「AI 已读」印章。
 * setReveal() 用 clipRect 做逐行吐纸；shatter() 用 ObjectAnimator 让每个内容块碎裂飞散。
 */
public class ReceiptView extends View {

    public static class Item {
        public String name = "";
        public String amount = "";
        public boolean income;
    }

    public static class Data {
        public String sub = "";
        public String metaLeft = "";
        public String metaRight = "";
        public String totalLabel = "本期支出";
        public String total = "0.00";
        public String cap = "";
        /** < 0 不画进度条 */
        public float budgetRatio = -1f;
        public final List<Item> items = new ArrayList<>();
        public final List<String[]> pcts = new ArrayList<>();
        public String aiTitle = "AI 月度总结";
        public String aiText = "";
        public final List<String> aiList = new ArrayList<>();
        public String footLeft = "";
        public String footMid = "";
        public String footRight = "";
        public String codeNo = "";
        public String stamp1 = "AI 已读";
        public String stamp2 = "";
        public String thanks = "谢谢惠顾 · 一句话，记一笔";
    }

    private static final int PAPER = 0xFFFFFCF3;
    private static final int INK = 0xFF23201B;
    private static final int MUTED = 0xFF6E675A;
    private static final int FAINT = 0xFF8C8578;
    private static final int RULE = 0xFFC9C2B2;
    private static final int BODY = 0xFF4A4438;
    private static final int BLUE = 0xFF3E86C9;
    private static final int GREEN = 0xFF2E8B57;

    private final float density;
    private Data data;
    private float reveal = 1f;
    private int builtWidth = -1;
    private float contentHeight;
    private int surround = 0xFFE9E2D4;
    private boolean blank;
    private AnimatorSet shatterAnim;

    private final Paint paperPaint = new Paint();
    private final List<Block> blocks = new ArrayList<>();

    /** 内容块动画载体：public 静态类 + public 字段，ObjectAnimator 可直接驱动。 */
    public static class BlockAnim {
        public float dx;
        public float dy;
        public float rot;
        public float alpha = 1f;

        public void setDx(float v) {
            dx = v;
        }

        public void setDy(float v) {
            dy = v;
        }

        public void setRot(float v) {
            rot = v;
        }

        public void setAlpha(float v) {
            alpha = v;
        }

        public float getAlpha() {
            return alpha;
        }
    }

    private static class Block {
        float top;
        float bottom;
        BlockAnim anim = new BlockAnim();
        Drawer drawer;
    }

    private interface Drawer {
        void draw(Canvas canvas);
    }

    public ReceiptView(Context context) {
        this(context, null);
    }

    public ReceiptView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        paperPaint.setColor(PAPER);
        paperPaint.setStyle(Paint.Style.FILL);
    }

    public void setSurroundColor(int color) {
        surround = color;
        invalidate();
    }

    public void setData(Data d) {
        cancelShatter();
        data = d;
        builtWidth = -1;
        blocks.clear();
        contentHeight = 0;
        reveal = 1f;
        requestLayout();
        invalidate();
    }

    /** 思考 / 碎裂期间把整张纸藏起来，避免露出空白纸面。 */
    public void setBlank(boolean b) {
        blank = b;
        invalidate();
    }

    public void setReveal(float f) {
        reveal = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    public float getReveal() {
        return reveal;
    }

    public void cancelShatter() {
        if (shatterAnim != null) {
            shatterAnim.cancel();
            shatterAnim = null;
        }
    }

    /** 逐块碎裂：位移 + 旋转 + 透明度，按内容块错峰。 */
    public void shatter(final Runnable end) {
        cancelShatter();
        if (blocks.isEmpty()) {
            if (end != null) end.run();
            return;
        }
        List<Animator> anims = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            b.anim.dx = 0;
            b.anim.dy = 0;
            b.anim.rot = 0;
            b.anim.alpha = 1f;
            float dx = (float) (Math.random() - 0.5) * dp(76);
            float dy = dp(18) + (float) Math.random() * dp(96);
            float rot = (float) (Math.random() - 0.5) * 24f;
            ObjectAnimator ax = ObjectAnimator.ofFloat(b.anim, "dx", 0f, dx);
            ObjectAnimator ay = ObjectAnimator.ofFloat(b.anim, "dy", 0f, dy);
            ObjectAnimator ar = ObjectAnimator.ofFloat(b.anim, "rot", 0f, rot);
            ObjectAnimator aa = ObjectAnimator.ofFloat(b.anim, "alpha", 1f, 0f);
            ValueAnimator[] one = {ax, ay, ar, aa};
            for (ValueAnimator a : one) {
                a.setDuration(620);
                a.setStartDelay(i * 12L);
                a.setInterpolator(new AccelerateDecelerateInterpolator());
                a.addUpdateListener(anim -> postInvalidateOnAnimation());
                anims.add(a);
            }
        }
        shatterAnim = new AnimatorSet();
        shatterAnim.playTogether(anims);
        shatterAnim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                shatterAnim = null;
                if (!cancelled && end != null) end.run();
            }
        });
        shatterAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelShatter();
        super.onDetachedFromWindow();
    }

    /* ===================== 测量与构建 ===================== */

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) width = (int) dp(320);
        if (width != builtWidth) build(width);
        int height = (int) Math.ceil(contentHeight);
        setMeasuredDimension(width, Math.max(height, (int) dp(120)));
    }

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private Paint p(float sizeSp, int color, boolean mono, boolean bold) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(sp(sizeSp));
        paint.setColor(color);
        paint.setTypeface(Typeface.create(mono ? Typeface.MONOSPACE : Typeface.SANS_SERIF,
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return paint;
    }

    private Paint center(Paint paint) {
        paint.setTextAlign(Paint.Align.CENTER);
        return paint;
    }

    private float addBlock(float top, float height, Drawer drawer) {
        Block b = new Block();
        b.top = top;
        b.bottom = top + height;
        b.drawer = drawer;
        blocks.add(b);
        return b.bottom;
    }

    private void build(int width) {
        builtWidth = width;
        blocks.clear();
        if (data == null) {
            contentHeight = dp(180);
            return;
        }
        final float W = width;
        final float padX = dp(18);
        final float innerW = W - padX * 2;
        final float cx = W / 2f;
        float y = 0;

        // 撕口
        y = addBlock(y, dp(10), canvas -> {
            Paint notch = new Paint(Paint.ANTI_ALIAS_FLAG);
            notch.setColor(surround);
            for (float x = dp(9); x < W; x += dp(18)) {
                canvas.drawCircle(x, 0, dp(6), notch);
            }
        });

        // 票头
        final float headTop = y;
        y = addBlock(headTop, dp(56), canvas -> {
            Paint nm = center(p(13, INK, false, true));
            nm.setLetterSpacing(0.4f);
            canvas.drawText("羊毛账本", cx, headTop + dp(20), nm);
            Paint sub = center(p(7.5f, FAINT, false, false));
            sub.setLetterSpacing(0.26f);
            canvas.drawText(data.sub, cx, headTop + dp(38), sub);
        });

        // 日期 / 单号
        final float metaTop = y;
        y = addBlock(metaTop, dp(18), canvas -> {
            Paint mono = p(10, FAINT, true, false);
            canvas.drawText(data.metaLeft, padX, metaTop + dp(13), mono);
            Paint right = p(10, FAINT, true, false);
            right.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(data.metaRight, W - padX, metaTop + dp(13), right);
        });

        // 虚线
        y = dashedBlock(y, W, padX);

        // 合计标题
        final float labelTop = y;
        y = addBlock(labelTop, dp(20), canvas -> {
            Paint label = center(p(10.5f, FAINT, false, false));
            label.setLetterSpacing(0.22f);
            canvas.drawText(data.totalLabel, cx, labelTop + dp(14), label);
        });

        // 大号合计
        final float totalTop = y;
        y = addBlock(totalTop, dp(46), canvas -> {
            Paint total = center(p(38, INK, true, true));
            canvas.drawText("¥" + data.total, cx, totalTop + dp(36), total);
        });

        // 预算说明
        final float capTop = y;
        y = addBlock(capTop, dp(17), canvas -> {
            Paint cap = center(p(10, FAINT, true, false));
            cap.setLetterSpacing(0.04f);
            canvas.drawText(data.cap, cx, capTop + dp(12), cap);
        });

        // 预算进度
        if (data.budgetRatio >= 0) {
            final float barTop = y;
            y = addBlock(barTop, dp(15), canvas -> {
                Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
                border.setStyle(Paint.Style.STROKE);
                border.setStrokeWidth(Math.max(1f, dp(1)));
                border.setColor(INK);
                RectF box = new RectF(padX, barTop + dp(2), W - padX, barTop + dp(12));
                canvas.drawRect(box, border);
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(INK);
                float ratio = Math.max(0f, Math.min(1f, data.budgetRatio));
                canvas.drawRect(padX + dp(1), barTop + dp(3),
                        padX + dp(1) + (W - padX * 2 - dp(2)) * ratio, barTop + dp(11), fill);
            });
        }

        y = dashedBlock(y, W, padX);

        // 明细
        for (final Item item : data.items) {
            final float itemTop = y;
            y = addBlock(itemTop, dp(22), canvas -> {
                Paint name = p(10.5f, item.income ? INK : BODY, false, item.income);
                canvas.drawText(item.name, padX, itemTop + dp(15), name);
                Paint amount = p(11.5f, item.income ? GREEN : INK, true, true);
                amount.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(item.amount, W - padX, itemTop + dp(15), amount);
                float nameEnd = padX + name.measureText(item.name) + dp(5);
                float amountStart = W - padX - amount.measureText(item.amount) - dp(5);
                if (amountStart > nameEnd) {
                    Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
                    dots.setColor(RULE);
                    dots.setStrokeWidth(Math.max(1f, dp(1)));
                    dots.setPathEffect(new DashPathEffect(new float[]{dp(1f), dp(2.4f)}, 0));
                    canvas.drawLine(nameEnd, itemTop + dp(13), amountStart, itemTop + dp(13), dots);
                }
            });
        }

        // 分类占比
        if (!data.pcts.isEmpty()) {
            y = dashedBlock(y, W, padX);
            final float pctTop = y;
            y = addBlock(pctTop, dp(22), canvas -> {
                float colW = innerW / data.pcts.size();
                for (int i = 0; i < data.pcts.size(); i++) {
                    String[] pair = data.pcts.get(i);
                    float x = padX + colW * i;
                    Paint name = p(10, MUTED, false, false);
                    canvas.drawText(pair[0], x, pctTop + dp(15), name);
                    Paint pct = p(10.5f, INK, false, true);
                    canvas.drawText(pair.length > 1 ? pair[1] : "",
                            x + name.measureText(pair[0]) + dp(3), pctTop + dp(15), pct);
                }
            });
        }

        // AI 总结
        Paint titlePaint = p(10, INK, false, true);
        titlePaint.setLetterSpacing(0.14f);
        Paint bodyPaint = p(10.5f, BODY, false, false);
        final float boxPad = dp(9);
        final float innerText = innerW - boxPad * 2;
        List<String> para = wrap(data.aiText, bodyPaint, innerText);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < data.aiList.size(); i++) {
            List<String> lines = wrap(data.aiList.get(i), bodyPaint, innerText - dp(12));
            for (int j = 0; j < lines.size(); j++) {
                list.add((j == 0 ? (i + 1) + ". " : "   ") + lines.get(j));
            }
        }
        float paraH = para.size() * dp(16);
        float listH = list.size() * dp(16.5f);
        float boxH = boxPad * 2 + dp(18) + paraH + (list.isEmpty() ? 0 : dp(4) + listH);
        final float boxTop = y;
        y = addBlock(boxTop, boxH, canvas -> {
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, dp(1)));
            border.setColor(RULE);
            canvas.drawRect(new RectF(padX, boxTop, W - padX, boxTop + boxH), border);
            canvas.drawText(data.aiTitle, padX + boxPad, boxTop + boxPad + dp(10), titlePaint);
            float lineY = boxTop + boxPad + dp(18);
            for (String line : para) {
                lineY += dp(16);
                canvas.drawText(line, padX + boxPad, lineY - dp(5), bodyPaint);
            }
            if (!list.isEmpty()) lineY += dp(4);
            for (String line : list) {
                lineY += dp(16.5f);
                canvas.drawText(line, padX + boxPad, lineY - dp(5), bodyPaint);
            }
        });

        // 页脚
        y = dashedBlock(y, W, padX);
        final float footTop = y;
        y = addBlock(footTop, dp(22), canvas -> {
            Paint foot = p(10, MUTED, false, false);
            canvas.drawText(data.footLeft, padX, footTop + dp(15), foot);
            Paint mid = p(10, MUTED, false, false);
            mid.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(data.footMid, cx, footTop + dp(15), mid);
            Paint right = p(10, MUTED, false, false);
            right.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(data.footRight, W - padX, footTop + dp(15), right);
        });

        // 条码
        final float codeTop = y;
        y = addBlock(codeTop, dp(54), canvas -> {
            Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
            bar.setColor(INK);
            String digits = data.codeNo.replace(" ", "");
            float x = padX;
            // 右侧给「AI 已读」印章留出空白，避免压在条码上
            float maxX = W - padX - dp(86);
            int seed = 7;
            int i = 0;
            while (x < maxX && digits.length() > 0) {
                char ch = digits.charAt(i % digits.length());
                seed = (seed * 31 + ch) & 0x7fffffff;
                float wBar = dp(1 + (seed % 3));
                float gap = dp(1 + ((seed >> 3) % 2));
                if (x + wBar > maxX) break;
                canvas.drawRect(x, codeTop + dp(6), x + wBar, codeTop + dp(40), bar);
                x += wBar + gap;
                i++;
            }
            Paint no = center(p(8, FAINT, true, false));
            no.setLetterSpacing(0.3f);
            canvas.drawText(data.codeNo, cx, codeTop + dp(50), no);
        });

        // 谢谢惠顾
        final float thanksTop = y;
        y = addBlock(thanksTop, dp(30), canvas -> {
            Paint thanks = center(p(10.5f, FAINT, false, false));
            thanks.setLetterSpacing(0.18f);
            canvas.drawText(data.thanks, cx, thanksTop + dp(17), thanks);
        });

        // 印章（按预览稿压在票面右下角，留出右/下安全边距，并避开条码）
        final float stampCx = W - padX - dp(52);
        final float stampCy = y - dp(40);
        addBlock(stampCy - dp(34), dp(86), canvas -> {
            canvas.save();
            canvas.rotate(-9f, stampCx, stampCy);
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Math.max(1.5f, dp(1.6f)));
            ring.setColor(BLUE);
            canvas.drawCircle(stampCx, stampCy, dp(29), ring);
            Paint one = center(p(9.5f, BLUE, false, true));
            one.setLetterSpacing(0.08f);
            canvas.drawText(data.stamp1, stampCx, stampCy - dp(3), one);
            Paint two = center(p(8.5f, BLUE, false, true));
            canvas.drawText(data.stamp2, stampCx, stampCy + dp(10), two);
            Paint wool = center(p(5.5f, BLUE, false, false));
            wool.setLetterSpacing(0.14f);
            canvas.drawText("WOOL", stampCx, stampCy + dp(22), wool);
            canvas.restore();
        });

        contentHeight = y + dp(12);
    }

    private float dashedBlock(float top, float width, float padX) {
        return addBlock(top, dp(12), canvas -> {
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(RULE);
            line.setStrokeWidth(Math.max(1f, dp(1)));
            line.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(2.4f)}, 0));
            canvas.drawLine(padX, top + dp(6), width - padX, top + dp(6), line);
        });
    }

    private List<String> wrap(String text, Paint paint, float maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.length() == 0) return out;
        String[] paragraphs = text.split("\n");
        for (String paragraph : paragraphs) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                char ch = paragraph.charAt(i);
                line.append(ch);
                if (paint.measureText(line.toString()) > maxWidth) {
                    line.deleteCharAt(line.length() - 1);
                    if (line.length() > 0) out.add(line.toString().trim());
                    line.setLength(0);
                    if (ch != ' ') line.append(ch);
                }
            }
            if (line.length() > 0) out.add(line.toString().trim());
        }
        return out;
    }

    /* ===================== 绘制 ===================== */

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (blank || data == null || blocks.isEmpty()) return;
        final int w = getWidth();
        float revealY = reveal >= 1f ? contentHeight
                : Math.round(contentHeight * reveal / dp(6)) * dp(6);
        revealY = Math.max(0, Math.min(contentHeight, revealY));

        int save = canvas.save();
        if (reveal < 1f) canvas.clipRect(0, 0, w, revealY);
        canvas.drawRect(0, 0, w, contentHeight, paperPaint);

        for (Block b : blocks) {
            boolean animated = b.anim.dx != 0 || b.anim.dy != 0 || b.anim.rot != 0
                    || b.anim.alpha < 0.999f;
            if (!animated) {
                b.drawer.draw(canvas);
                continue;
            }
            int s = canvas.save();
            canvas.clipRect(0, b.top, w, b.bottom);
            canvas.translate(b.anim.dx, b.anim.dy);
            canvas.rotate(b.anim.rot, w / 2f, (b.top + b.bottom) / 2f);
            int layer = canvas.saveLayerAlpha(0, b.top, w, b.bottom,
                    (int) (255 * Math.max(0f, Math.min(1f, b.anim.alpha))));
            b.drawer.draw(canvas);
            canvas.restoreToCount(layer);
            canvas.restoreToCount(s);
        }
        canvas.restoreToCount(save);

        if (reveal < 1f && revealY > 0) {
            Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
            head.setColor(0xFFFFFFFF);
            head.setStrokeWidth(dp(2));
            canvas.drawLine(0, revealY - dp(1), w, revealY - dp(1), head);
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setColor(0x66FFFFFF);
            glow.setStrokeWidth(dp(6));
            canvas.drawLine(0, revealY + dp(2), w, revealY + dp(2), glow);
        }
    }
}
