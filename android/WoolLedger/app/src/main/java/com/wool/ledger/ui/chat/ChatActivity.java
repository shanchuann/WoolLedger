package com.wool.ledger.ui.chat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wool.ledger.R;
import com.wool.ledger.util.ThemeUtil;
import com.wool.ledger.ai.LlmClient;
import com.wool.ledger.ai.LlmResult;
import com.wool.ledger.data.Bill;
import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Category;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.data.Seed;
import com.wool.ledger.ui.add.AddRecordActivity;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Money;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蓝羊羊：首屏演一段「它在读账 → 你说一句 → 它开一张待确认小票」，
 * 之后每句话都带上本月账本摘要交给 LlmClient.chat，回车/发送后显示三点输入指示。
 * 卡片「记上 / 保密」写一条 source=1 的账（保密只存「保密」二字，不存原话）。
 */
public class ChatActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private BillDao dao;
    private ChatAdapter adapter;
    private RecyclerView log;
    private EditText input;
    private TextView sub;

    private ActivityResultLauncher<Intent> editLauncher;
    private ChatAdapter.Msg pendingCardMsg;

    private boolean seeded;
    private int monthCount;
    private String monthText = "10";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.applyTheme(this);
        setContentView(R.layout.activity_chat);
        dao = new BillDao(this);
        monthText = String.valueOf(Integer.parseInt(DateUtil.thisMonth().substring(5, 7)));

        applyDarkIfNeeded();
        setupBackdrop();
        bindViews();
        setupLog();
        setupInput();
        setupSuggestions();
        setupEditLauncher();

        refreshHeaderAndSeed();
    }

    /* ---------------- 骨架 ---------------- */

    private void bindViews() {
        log = findViewById(R.id.chat_log);
        input = findViewById(R.id.chat_input);
        sub = findViewById(R.id.chat_sub);
        findViewById(R.id.chat_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    /** 纸底 + 44dp 网格，和预览的 radial paper 同一套。 */
    private void setupBackdrop() {
        // 背景和其它页面一致：只要纸色，不加渐变也不画网格
        View root = findViewById(R.id.chat_frame);
        if (root != null) root.setBackgroundColor(getColor(com.wool.ledger.data.Prefs.dark(this) ? R.color.paper_dark : R.color.paper));
    }

    private void setupLog() {
        adapter = new ChatAdapter(new ChatAdapter.Listener() {
            @Override
            public void onApprove(ChatAdapter.Msg msg, int action) {
                onCardAction(msg, action);
            }
        });
        log.setLayoutManager(new LinearLayoutManager(this));
        log.setAdapter(adapter);
        // 键盘出现 / 收起时列表高度会变，跟着滚到最新一条，避免输入框和最后一句被顶出屏幕
        log.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((bottom - top) != (oldBottom - oldTop)) scrollToEnd();
            }
        });
    }

    private void setupInput() {
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(dp(18));
        box.setColor(0xFFFFFFFF);
        box.setStroke(dp(1), 0xFF1A1F24);
        findViewById(R.id.chat_box).setBackground(box);

        ImageView send = findViewById(R.id.chat_send);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ContextCompat.getColor(this, R.color.ink));
        send.setBackground(circle);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(input.getText().toString());
            }
        });

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send(input.getText().toString());
                    return true;
                }
                return false;
            }
        });
    }

    private void setupSuggestions() {
        View.OnClickListener ask = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String q = ((TextView) v).getText().toString();
                input.setText(q);
                send(q);
            }
        };
        findViewById(R.id.chat_sugg1).setOnClickListener(ask);
        findViewById(R.id.chat_sugg2).setOnClickListener(ask);
        findViewById(R.id.chat_sugg3).setOnClickListener(ask);
    }

    private void setupEditLauncher() {
        editLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (result.getResultCode() != RESULT_OK) {
                            pendingCardMsg = null;
                            return;
                        }
                        if (pendingCardMsg != null && pendingCardMsg.card != null) {
                            pendingCardMsg.card.done = true;
                            adapter.update(pendingCardMsg);
                            ChatAdapter.Msg ai = ChatAdapter.Msg.ai("改好了，已经记进账本。");
                            adapter.add(ai);
                            scrollToEnd();
                        }
                        pendingCardMsg = null;
                        setResult(RESULT_OK, new Intent().putExtra("changed", true));
                        refreshHeaderCount();
                    }
                });
    }

    /* ---------------- 页头 + 首屏 ---------------- */

    private void refreshHeaderAndSeed() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                Seed.maybeSeed(ChatActivity.this);
                final int cnt = countThisMonth();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        monthCount = cnt;
                        updateHeader();
                        seedChat();
                    }
                });
            }
        });
    }

    private void refreshHeaderCount() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final int cnt = countThisMonth();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        monthCount = cnt;
                        updateHeader();
                    }
                });
            }
        });
    }

    private int countThisMonth() {
        String ym = DateUtil.thisMonth();
        String from = ym + "-01";
        String to = ym + "-" + String.format(java.util.Locale.CHINA, "%02d", DateUtil.daysInMonth(ym));
        return dao.count(from, to);
    }

    private void updateHeader() {
        sub.setText("正在读你 " + monthText + " 月的 " + monthCount + " 笔账");
    }

    private void seedChat() {
        if (seeded) return;
        seeded = true;
        // 不预置任何对话，只留一句开场白
        ChatAdapter.Msg hello = ChatAdapter.Msg.ai(
                "请输入账目内容，例如「午饭 15 元」。");
        adapter.add(hello);
    }

    /* ---------------- 发送 / 回复 ---------------- */

    private void send(String text) {
        final String q = text == null ? "" : text.trim();
        if (q.isEmpty()) return;
        input.setText("");

        adapter.add(ChatAdapter.Msg.me(q));
        scrollToEnd();

        final ChatAdapter.Msg typing = ChatAdapter.Msg.typing();
        adapter.add(typing);
        scrollToEnd();

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        final String digest = buildDigest();
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing() || isDestroyed()) return;
                                LlmClient.chat(ChatActivity.this, q, digest, new LlmClient.Callback() {
                                    @Override
                                    public void onResult(LlmResult r) {
                                        if (isFinishing() || isDestroyed()) return;
                                        adapter.remove(typing);
                                        String body = (r != null && r.ok && r.text != null
                                                && !r.text.trim().isEmpty())
                                                ? r.text.trim()
                                                : "我在。说一句账，或者问「这个月还能花多少」。";
                                        adapter.add(ChatAdapter.Msg.ai(body));
                                        scrollToEnd();
                                    }
                                });
                            }
                        });
                    }
                });
            }
        }, 650);
    }

    /** 本月账本摘要：总额 / 笔数 / 分类前三，交给模型当上下文。 */
    private String buildDigest() {
        String ym = DateUtil.thisMonth();
        String from = ym + "-01";
        String to = ym + "-" + String.format(java.util.Locale.CHINA, "%02d", DateUtil.daysInMonth(ym));
        double[] t = dao.totals(from, to);
        int cnt = dao.count(from, to);
        Map<String, Double> by = dao.byCategory(from, to);

        StringBuilder sb = new StringBuilder();
        sb.append("账期 ").append(ym).append("，共 ").append(cnt).append(" 笔；");
        sb.append("支出 ¥").append(Money.fmt(t[0])).append("，收入 ¥").append(Money.fmt(t[1])).append("；");
        if (!by.isEmpty()) {
            sb.append("支出分类前三：");
            int n = 0;
            for (Map.Entry<String, Double> e : by.entrySet()) {
                double pct = t[0] > 0 ? e.getValue() / t[0] * 100 : 0;
                if (n > 0) sb.append("、");
                sb.append(Category.nameOf(e.getKey())).append(" ¥").append(Money.fmt(e.getValue()))
                        .append("（").append(Math.round(pct)).append("%）");
                n++;
                if (n >= 3) break;
            }
            sb.append("；");
        }
        return sb.toString();
    }

    /* ---------------- 确认卡片 ---------------- */

    private void onCardAction(ChatAdapter.Msg msg, int action) {
        if (msg == null || msg.card == null || msg.card.done) return;
        if (action == 2) {
            openEdit(msg);
            return;
        }
        boolean secret = action == 1;
        msg.card.secret = secret;
        msg.card.done = true;
        adapter.update(msg);
        recordCard(msg.card);
    }

    private void recordCard(final ChatAdapter.Card c) {
        final Bill b = new Bill();
        b.type = Bill.EXPENSE;
        b.amount = c.amount;
        b.category = Category.byCode(c.catCode).code;
        b.note = c.secret ? "保密"
                : (c.raw != null && !c.raw.trim().isEmpty() ? c.raw.trim() : Category.nameOf(c.catCode));
        b.billDate = c.date != null && c.date.length() == 10 ? c.date : DateUtil.today();
        b.billTime = DateUtil.nowTime();
        b.slot = c.slot == null || c.slot.isEmpty() ? "午" : c.slot;
        b.createTime = DateUtil.nowFull();
        b.source = 1;

        io.execute(new Runnable() {
            @Override
            public void run() {
                dao.insert(b);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        setResult(RESULT_OK, new Intent().putExtra("changed", true));
                        String say = c.secret
                                ? "记好了，按保密处理：只留金额、分类和时段。"
                                : "记好了：" + "[[ " + Category.nameOf(b.category) + " ¥" + Money.fmt(b.amount) + " ]]"
                                + "，落在周" + DateUtil.weekdayCn(b.billDate) + " · " + b.slot + "那一格。";
                        adapter.add(ChatAdapter.Msg.ai(say));
                        scrollToEnd();
                        refreshHeaderCount();
                    }
                });
            }
        });
    }

    private void openEdit(ChatAdapter.Msg msg) {
        ChatAdapter.Card c = msg.card;
        pendingCardMsg = msg;
        Intent it = new Intent(this, AddRecordActivity.class);
        it.putExtra(AddRecordActivity.EXTRA_BILL_ID, 0L);
        it.putExtra("date", c.date);
        it.putExtra("slot", c.slot);
        it.putExtra("amount", c.amount);
        it.putExtra("type", Bill.EXPENSE);
        it.putExtra("category", c.catCode);
        it.putExtra("note", c.raw);
        it.putExtra("source", 1);
        editLauncher.launch(it);
    }

    /* ---------------- 小工具 ---------------- */

    private void scrollToEnd() {
        log.post(new Runnable() {
            @Override
            public void run() {
                if (adapter.getItemCount() > 0) {
                    log.scrollToPosition(adapter.getItemCount() - 1);
                }
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    /** 44dp 一张纸的网格，压在渐变底色上。 */
    private static class ChatBackdrop extends View {
        private final Paint paint = new Paint();

        ChatBackdrop(Context ctx) {
            super(ctx);
            paint.setColor(0x66E3E7E1);
            paint.setStrokeWidth(ChatAdapter.dp(ctx, 1));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float step = ChatAdapter.dp(getContext(), 44);
            for (float x = 0; x <= getWidth(); x += step) {
                canvas.drawLine(x, 0, x, getHeight(), paint);
            }
            for (float y = 0; y <= getHeight(); y += step) {
                canvas.drawLine(0, y, getWidth(), y, paint);
            }
        }
    }

    /** 深色模式：把这一页的纸色/墨色/发丝线统一重涂。 */
    private void applyDarkIfNeeded() {
        if (!com.wool.ledger.util.ThemeUtil.dark(this)) return;
        android.view.View root = getWindow().getDecorView().findViewById(android.R.id.content);
        root.setBackgroundColor(com.wool.ledger.util.ThemeUtil.paper(this));
        tintTree(root);
    }

    private void tintTree(android.view.View v) {
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            int color = t.getCurrentTextColor();
            if (color == 0xFF1A1F24) t.setTextColor(0xFFF3EFE4);
            else if (color == 0xFF5C6166) t.setTextColor(0xFFB7B2A5);
            else if (color == 0xFF6B7076) t.setTextColor(0xFF8F9AA6);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tintTree(g.getChildAt(i));
        }
    }
}
