package com.wool.ledger.ui.chat;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.wool.ledger.R;
import com.wool.ledger.data.Category;
import com.wool.ledger.util.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * 蓝羊羊对话列表：三种行——我说的话（蓝气泡）、它说的话（轨迹 + 正文 + 确认卡片）、
 * 正在输入（三个点）。记账卡片的三个动作回调给 ChatActivity。
 */
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int WHO_ME = 0;
    public static final int WHO_AI = 1;

    private static final int VT_ME = 0;
    private static final int VT_AI = 1;
    private static final int VT_TYPING = 2;

    /** 动作：0 记上 / 1 保密 / 2 改一下 */
    public interface Listener {
        void onApprove(Msg msg, int action);
    }

    /** 一张待确认的记账卡片。 */
    public static class Card {
        public long actionId;
        public String actionState="pending";
        public boolean generic;
        public double amount;
        public String catCode = "other";
        public String when = "";
        public String date = "";
        public String slot = "午";
        public String raw = "";
        public boolean done;
        public boolean secret;

        public static Card of(double amount, String catCode, String when, String date, String slot, String raw) {
            Card c = new Card();
            c.amount = amount;
            c.catCode = catCode;
            c.when = when;
            c.date = date;
            c.slot = slot;
            c.raw = raw;
            return c;
        }
    }

    /** 一条对话消息。 */
    public static class Msg {
        public int who = WHO_AI;
        public String text = "";
        public List<String> trace;
        public Card card;
        public boolean typing;

        public static Msg me(String text) {
            Msg m = new Msg();
            m.who = WHO_ME;
            m.text = text;
            return m;
        }

        public static Msg ai(String text) {
            Msg m = new Msg();
            m.who = WHO_AI;
            m.text = text;
            return m;
        }

        public static Msg typing() {
            Msg m = new Msg();
            m.who = WHO_AI;
            m.typing = true;
            return m;
        }
    }

    private final List<Msg> data = new ArrayList<>();
    private final Listener listener;

    public ChatAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    public List<Msg> data() {
        return data;
    }

    public void add(Msg m) {
        data.add(m);
        notifyItemInserted(data.size() - 1);
    }

    public void remove(Msg m) {
        int i = data.indexOf(m);
        if (i >= 0) {
            data.remove(i);
            notifyItemRemoved(i);
        }
    }

    public void update(Msg m) {
        int i = data.indexOf(m);
        if (i >= 0) notifyItemChanged(i);
    }

    @Override
    public int getItemViewType(int position) {
        Msg m = data.get(position);
        if (m.who == WHO_ME) return VT_ME;
        return m.typing ? VT_TYPING : VT_AI;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == VT_ME) {
            return new MeVH(inf.inflate(R.layout.item_chat_me, parent, false));
        }
        return new AiVH(inf.inflate(R.layout.item_chat_ai, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Msg m = data.get(position);
        if (holder instanceof MeVH) {
            ((MeVH) holder).body.setText(m.text);
            return;
        }
        bindAi((AiVH) holder, m);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof AiVH) ((AiVH) holder).stopDots();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    /* ---------------- 绑定 ---------------- */

    private void bindAi(AiVH h, Msg m) {
        Context ctx = h.itemView.getContext();
        h.stopDots();
        h.body.setTextColor(com.wool.ledger.util.ThemeUtil.ink(ctx));
        h.apTitle.setTextColor(com.wool.ledger.util.ThemeUtil.ink(ctx));h.apAmount.setTextColor(com.wool.ledger.util.ThemeUtil.ink(ctx));h.apCat.setTextColor(com.wool.ledger.util.ThemeUtil.ink3(ctx));h.apWhen.setTextColor(com.wool.ledger.util.ThemeUtil.ink3(ctx));

        boolean hasTrace = !m.typing && m.trace != null && !m.trace.isEmpty();
        h.traceBox.setVisibility(hasTrace ? View.VISIBLE : View.GONE);
        h.steps.removeAllViews();
        if (hasTrace) {
            for (int i = 0; i < m.trace.size(); i++) {
                h.steps.addView(traceStep(ctx, m.trace.get(i), i == m.trace.size() - 1));
            }
        }

        boolean hasText = !m.typing && m.text != null && !m.text.isEmpty();
        h.body.setVisibility(hasText ? View.VISIBLE : View.GONE);
        if (hasText) h.body.setText(styled(m.text));

        boolean hasCard = !m.typing && m.card != null;
        h.approve.setVisibility(hasCard ? View.VISIBLE : View.GONE);
        if (hasCard) bindCard(h, m);

        h.dotsBox.setVisibility(m.typing ? View.VISIBLE : View.GONE);
        if (m.typing) h.startDots();
    }

    private TextView traceStep(Context ctx, String text, boolean last) {
        SpannableStringBuilder sb = new SpannableStringBuilder("✓  " + text);
        sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.green)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView tv = new TextView(ctx);
        tv.setText(sb);
        tv.setTextSize(11f);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.ink2));
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!last) lp.bottomMargin = dp(ctx, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void bindCard(final AiVH h, final Msg m) {
        final Card c = m.card;
        if(c.generic) {
            boolean pending="pending".equals(c.actionState), applied="applied".equals(c.actionState);
            h.apTitle.setText(pending?"待确认操作":applied?"已执行":"已结束");
            h.apAmount.setText("操作 #"+c.actionId);h.apCat.setText(pending?"确认后才会修改账本":applied?"可以撤销本次操作":"账本保持当前状态");h.apWhen.setText("");
            h.acts.setVisibility(pending||applied?View.VISIBLE:View.GONE);h.ok.setText(applied?"撤销":"确认执行");h.secret.setText("取消");h.secret.setVisibility(pending?View.VISIBLE:View.GONE);h.edit.setVisibility(View.GONE);
            h.ok.setOnClickListener(v->{if(listener!=null)listener.onApprove(m,applied?3:0);});h.secret.setOnClickListener(v->{if(listener!=null)listener.onApprove(m,1);});return;
        }
        h.secret.setVisibility(View.VISIBLE);h.edit.setVisibility(View.VISIBLE);h.ok.setText("记上");h.secret.setText("保密");
        h.apTitle.setText(c.done ? (c.secret ? "已写入账本 · 保密" : "已写入账本") : "等你确认");
        h.apAmount.setText("¥" + Money.fmt(c.amount));
        h.apCat.setText(Category.nameOf(c.catCode));
        h.apWhen.setText(c.when);
        h.acts.setVisibility(c.done ? View.GONE : View.VISIBLE);

        h.ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!c.done && listener != null) listener.onApprove(m, 0);
            }
        });
        h.secret.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!c.done && listener != null) listener.onApprove(m, 1);
            }
        });
        h.edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!c.done && listener != null) listener.onApprove(m, 2);
            }
        });
    }

    /** 支持 [[高亮]] 与 **加粗** 的极简标记；纯文本原样显示。 */
    private CharSequence styled(String s) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0;
        while (i < s.length()) {
            int hl = s.indexOf("[[", i);
            int bd = s.indexOf("**", i);
            boolean isHl;
            int start;
            if (hl >= 0 && (bd < 0 || hl < bd)) {
                start = hl;
                isHl = true;
            } else if (bd >= 0) {
                start = bd;
                isHl = false;
            } else {
                sb.append(s, i, s.length());
                break;
            }
            sb.append(s, i, start);
            int end = s.indexOf(isHl ? "]]" : "**", start + 2);
            if (end < 0) {
                sb.append(s, start, s.length());
                break;
            }
            int from = sb.length();
            sb.append(s, start + 2, end);
            if (isHl) {
                sb.setSpan(new BackgroundColorSpan(0x99F5E663), from, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new StyleSpan(Typeface.BOLD), from, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = end + 2;
        }
        return sb;
    }

    /* ---------------- ViewHolder ---------------- */

    static class MeVH extends RecyclerView.ViewHolder {
        final TextView body;

        MeVH(View v) {
            super(v);
            body = v.findViewById(R.id.chat_me_body);
            Context ctx = v.getContext();
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadii(new float[]{
                    dp(ctx, 14), dp(ctx, 14),
                    dp(ctx, 14), dp(ctx, 14),
                    dp(ctx, 3), dp(ctx, 3),
                    dp(ctx, 14), dp(ctx, 14)});
            g.setColor(ContextCompat.getColor(ctx, R.color.blue2));
            body.setBackground(g);
            body.setPadding(dp(ctx, 13), dp(ctx, 10), dp(ctx, 13), dp(ctx, 10));
            v.setPadding(0, dp(ctx, 1), 0, dp(ctx, 1));
        }
    }

    class AiVH extends RecyclerView.ViewHolder {
        final LinearLayout traceBox;
        final LinearLayout steps;
        final TextView body;
        final LinearLayout approve;
        final TextView apTitle;
        final TextView apAmount;
        final TextView apCat;
        final TextView apWhen;
        final LinearLayout acts;
        final TextView ok;
        final TextView secret;
        final TextView edit;
        final LinearLayout dotsBox;
        final View[] dots;
        final List<ObjectAnimator> anims = new ArrayList<>();

        AiVH(View v) {
            super(v);
            Context ctx = v.getContext();
            traceBox = v.findViewById(R.id.chat_ai_trace);
            steps = v.findViewById(R.id.chat_ai_steps);
            body = v.findViewById(R.id.chat_ai_body);
            approve = v.findViewById(R.id.chat_ai_approve);
            apTitle = v.findViewById(R.id.chat_ap_title);
            apAmount = v.findViewById(R.id.chat_ap_amount);
            apCat = v.findViewById(R.id.chat_ap_cat);
            apWhen = v.findViewById(R.id.chat_ap_when);
            acts = v.findViewById(R.id.chat_ap_acts);
            ok = v.findViewById(R.id.chat_ap_ok);
            secret = v.findViewById(R.id.chat_ap_secret);
            edit = v.findViewById(R.id.chat_ap_edit);
            dotsBox = v.findViewById(R.id.chat_ai_dots);
            dots = new View[]{v.findViewById(R.id.chat_dot1),
                    v.findViewById(R.id.chat_dot2), v.findViewById(R.id.chat_dot3)};

            traceBox.setBackground(new LeftLineDrawable(
                    ContextCompat.getColor(ctx, R.color.line), dp(ctx, 1)));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(ctx, 2));
            cardBg.setColor(com.wool.ledger.util.ThemeUtil.card(ctx));
            cardBg.setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.line));
            approve.setBackground(cardBg);

            GradientDrawable primary = new GradientDrawable();
            primary.setCornerRadius(dp(ctx, 3));
            primary.setColor(ContextCompat.getColor(ctx, R.color.ink));
            ok.setBackground(primary);
            ok.setTextColor(ContextCompat.getColor(ctx, R.color.paper));

            GradientDrawable ghost = new GradientDrawable();
            ghost.setCornerRadius(dp(ctx, 3));
            ghost.setColor(0x00000000);
            ghost.setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.line));
            secret.setBackground(ghost);
            edit.setBackground(ghost);
            secret.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
            edit.setTextColor(ContextCompat.getColor(ctx, R.color.ink));

            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(ContextCompat.getColor(ctx, R.color.ink3));
            for (View d : dots) d.setBackground(dotBg);
        }

        void startDots() {
            stopDots();
            Context ctx = itemView.getContext();
            for (int i = 0; i < dots.length; i++) {
                ObjectAnimator a = ObjectAnimator.ofFloat(dots[i], "translationY", 0f, -dp(ctx, 4), 0f);
                a.setDuration(900);
                a.setStartDelay(i * 150L);
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.RESTART);
                anims.add(a);
                a.start();
            }
        }

        void stopDots() {
            for (ObjectAnimator a : anims) a.cancel();
            anims.clear();
            for (View d : dots) d.setTranslationY(0f);
        }
    }

    /* ---------------- 小工具 ---------------- */

    static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** 只画左边一条竖线的 Drawable（思考轨迹的引导线）。 */
    static class LeftLineDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LeftLineDrawable(int color, float width) {
            paint.setColor(color);
            paint.setStrokeWidth(width);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawRect(0, 0, paint.getStrokeWidth(), getBounds().height(), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
