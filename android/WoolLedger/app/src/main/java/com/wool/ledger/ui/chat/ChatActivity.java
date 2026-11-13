package com.wool.ledger.ui.chat;

import android.content.*;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;

import com.wool.ledger.R;
import com.wool.ledger.agent.AgentEngine;
import com.wool.ledger.automation.PaymentInboxActivity;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.util.ThemeUtil;

import org.json.*;

import java.util.*;
import java.util.concurrent.*;

/** Durable conversation plus validated, explicitly confirmed local ledger tools. */
public class ChatActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AgentEngine engine;
    private ChatAdapter adapter;
    private RecyclerView log;
    private EditText input;
    private boolean busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Prefs.applyTheme(this);
        setContentView(R.layout.activity_chat);
        tintText(getWindow().getDecorView());
        engine = new AgentEngine(this);
        log = findViewById(R.id.chat_log);
        input = findViewById(R.id.chat_input);
        findViewById(R.id.chat_back).setOnClickListener(v -> finish());
        findViewById(R.id.chat_frame).setBackgroundColor(ThemeUtil.paper(this));
        input.setTextColor(ThemeUtil.ink(this));
        input.setHintTextColor(ThemeUtil.ink3(this));
        android.graphics.drawable.GradientDrawable box =
                new android.graphics.drawable.GradientDrawable();
        box.setColor(ThemeUtil.card(this));
        box.setCornerRadius(24 * getResources().getDisplayMetrics().density);
        box.setStroke(1, ThemeUtil.ink3(this));
        findViewById(R.id.chat_box).setBackground(box);
        android.graphics.drawable.GradientDrawable sendBg =
                new android.graphics.drawable.GradientDrawable();
        sendBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        sendBg.setColor(ThemeUtil.ink(this));
        findViewById(R.id.chat_send).setBackground(sendBg);
        ((ImageView) findViewById(R.id.chat_send)).setColorFilter(ThemeUtil.paper(this));
        TextView sub = findViewById(R.id.chat_sub);
        sub.setText("查账 · 记账 · 对账 · 操作需确认");
        sub.setTextColor(ThemeUtil.ink3(this));
        sub.setOnClickListener(v -> startActivity(new Intent(this, PaymentInboxActivity.class)));
        findViewById(R.id.chat_inbox)
                .setOnClickListener(
                        v -> startActivity(new Intent(this, PaymentInboxActivity.class)));
        adapter = new ChatAdapter(this::action);
        log.setLayoutManager(new LinearLayoutManager(this));
        log.setAdapter(adapter);
        findViewById(R.id.chat_send).setOnClickListener(v -> send(input.getText().toString()));
        input.setOnEditorActionListener(
                (v, id, event) -> {
                    if (id == EditorInfo.IME_ACTION_SEND) {
                        send(input.getText().toString());
                        return true;
                    }
                    return false;
                });
        int[] ids = {R.id.chat_sugg1, R.id.chat_sugg2, R.id.chat_sugg3};
        String[] qs = {"查本周支出", "帮我记：午饭15，地铁3", "本月对账"};
        for (int i = 0; i < ids.length; i++) {
            TextView t = findViewById(ids[i]);
            t.setText(qs[i]);
            t.setOnClickListener(v -> send(((TextView) v).getText().toString()));
        }
        load();
    }

    private void tintText(android.view.View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int color = text.getCurrentTextColor();
            if (color == 0xFF1A1F24) text.setTextColor(ThemeUtil.ink(this));
            else if (color == 0xFF5C6166 || color == 0xFF6B7076)
                text.setTextColor(ThemeUtil.ink3(this));
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintText(group.getChildAt(i));
        }
    }

    private void load() {
        busy = true;
        io.execute(
                () -> {
                    List<ContentValues> history = engine.history();
                    runOnUiThread(
                            () -> {
                                if (isFinishing() || isDestroyed()) return;
                                for (ContentValues m : history) {
                                    String body = m.getAsString("body");
                                    if ("user".equals(m.getAsString("role")))
                                        adapter.add(ChatAdapter.Msg.me(body));
                                    else {
                                        try {
                                            JSONObject o = new JSONObject(body);
                                            addReply(o.optString("text"), o.optLong("action_id"));
                                        } catch (Exception legacy) {
                                            adapter.add(ChatAdapter.Msg.ai(body));
                                        }
                                    }
                                }
                                if (history.isEmpty())
                                    adapter.add(
                                            ChatAdapter.Msg.ai(
                                                    "我是蓝羊羊"
                                                        + " Agent。可以帮你查账、汇总、对账，也能记账、修改或删除指定账目、调整预算。\n\n"
                                                        + "操作会先展示明细，确认后才执行，执行后可撤销。点击页头可查看支付通知待确认。"));
                                busy = false;
                                scroll();
                            });
                });
    }

    private void addReply(String text, long id) {
        ChatAdapter.Msg m = ChatAdapter.Msg.ai(text);
        if (id > 0) {
            m.card = new ChatAdapter.Card();
            m.card.generic = true;
            m.card.actionId = id;
            m.card.actionState = engine.actionState(id);
        }
        adapter.add(m);
    }

    private void send(String value) {
        String q = value.trim();
        if (q.isEmpty() || busy) return;
        if (q.length() > 2000) {
            Toast.makeText(this, "请将每次请求控制在2000字以内", Toast.LENGTH_LONG).show();
            return;
        }
        busy = true;
        input.setText("");
        adapter.add(ChatAdapter.Msg.me(q));
        ChatAdapter.Msg typing = ChatAdapter.Msg.typing();
        adapter.add(typing);
        scroll();
        io.execute(
                () -> {
                    try {
                        AgentEngine.Reply r = engine.handle(q);
                        runOnUiThread(
                                () -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    adapter.remove(typing);
                                    addReply(r.text, r.actionId);
                                    busy = false;
                                    scroll();
                                });
                    } catch (Exception failure) {
                        runOnUiThread(
                                () -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    adapter.remove(typing);
                                    adapter.add(
                                            ChatAdapter.Msg.ai(
                                                    "未执行："
                                                            + (failure.getMessage() == null
                                                                    ? "请重试"
                                                                    : failure.getMessage())));
                                    busy = false;
                                    scroll();
                                });
                    }
                });
    }

    private void action(ChatAdapter.Msg msg, int what) {
        if (busy || msg.card == null) return;
        busy = true;
        long id = msg.card.actionId;
        io.execute(
                () -> {
                    String result;
                    try {
                        if (what == 1) {
                            engine.cancel(id);
                            result = "已取消，未修改账本。";
                        } else result = engine.execute(id, what == 3);
                        engine.message(
                                "assistant", new JSONObject().put("text", result).toString());
                    } catch (Exception e) {
                        result = "未执行：" + e.getMessage();
                    }
                    final String text = result;
                    final String state = engine.actionState(id);
                    runOnUiThread(
                            () -> {
                                if (isFinishing() || isDestroyed()) return;
                                msg.card.actionState = state;
                                adapter.update(msg);
                                adapter.add(ChatAdapter.Msg.ai(text));
                                busy = false;
                                setResult(RESULT_OK, new Intent().putExtra("changed", true));
                                scroll();
                            });
                });
    }

    private void scroll() {
        log.post(
                () -> {
                    if (adapter.getItemCount() > 0)
                        log.scrollToPosition(adapter.getItemCount() - 1);
                });
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add("待确认").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        startActivity(new Intent(this, PaymentInboxActivity.class));
        return true;
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
