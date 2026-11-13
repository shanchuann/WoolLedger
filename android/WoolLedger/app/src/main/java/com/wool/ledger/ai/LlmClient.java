package com.wool.ledger.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.wool.ledger.data.Category;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.util.DateUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 大模型客户端：OpenAI 兼容 /chat/completions。
 * 线程池跑网络，Handler 回主线程；没配 Key 或出错时自动降级到 OfflineParser。
 */
public class LlmClient {

    public interface Callback {
        void onResult(LlmResult r);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int TIMEOUT = 20000;

    private static final String SYS_RECORD =
            "你是记账解析器。把用户的一句话解析成一条账目，只输出 JSON，不要解释。格式：" +
                    "{\"type\":0或1,\"amount\":数字,\"category\":分类code,\"note\":\"简短备注\",\"date\":\"yyyy-MM-dd\",\"slot\":\"早/午/晚/夜\"}。" +
                    "type: 0 支出 1 收入。category 取值：" +
                    "支出 food/transport/shop/study/fun/daily/stay/medical/other；收入 parttime/prize/gift/family/invest。" +
                    "日期默认今天，用户说昨天/前天要换算。" +
                    "今天日期：" ;

    private static final String SYS_REPORT =
            "你是大学生记账 App 的消费诊断助手。基于给定的账本摘要，写一段 120 字以内的消费画像（要具体到笔数和金额，不要空话），" +
                    "再给 4 条可执行建议，每条不超过 28 字。语气像同学，不像客服。输出纯文本，不要 Markdown 标记。";

    private static final String SYS_CHAT =
            "你是记账 App 里的助手「蓝羊羊」。基于账本摘要回答用户问题，口语化，简短，能给具体数字就给。" +
                    "如果用户是在说一笔账（比如「午饭 15」），友好地提示他可以点记一笔。";

    /** 一句话记账解析 */
    public static void parseRecord(final Context ctx, final String text, final Callback cb) {
        final String key = Prefs.apiKey(ctx);
        if (key.isEmpty()) {
            deliver(cb, OfflineParser.parse(text));
            return;
        }
        final String base = Prefs.apiBase(ctx);
        final String model = Prefs.apiModel(ctx);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                LlmResult out = null;
                try {
                    String raw = chatOnce(base, key, model,
                            SYS_RECORD + DateUtil.today() + "。用户说：" + text, true);
                    out = parseRecordJson(raw);
                } catch (Exception e) {
                    out = null;
                }
                if (out == null) {
                    out = OfflineParser.parse(text);
                    out.offline = true;
                }
                deliver(cb, out);
            }
        });
    }

    /** 月度诊断：digest 是一段账本摘要文字 */
    public static void monthReport(final Context ctx, final String digest, final Callback cb) {
        final String key = Prefs.apiKey(ctx);
        if (key.isEmpty()) {
            deliver(cb, LlmResult.text(LocalCopy.report(digest)));
            return;
        }
        final String base = Prefs.apiBase(ctx);
        final String model = Prefs.apiModel(ctx);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                LlmResult out;
                try {
                    String raw = chatOnce(base, key, model, SYS_REPORT + "\n\n账本摘要：\n" + digest, false);
                    out = LlmResult.text(raw.trim());
                } catch (Exception e) {
                    out = LlmResult.text(LocalCopy.report(digest));
                }
                deliver(cb, out);
            }
        });
    }

    /** 对话问答 */
    public static void chat(final Context ctx, final String question, final String digest, final Callback cb) {
        final String key = Prefs.apiKey(ctx);
        if (key.isEmpty()) {
            deliver(cb, LlmResult.text(LocalCopy.chat(question, digest)));
            return;
        }
        final String base = Prefs.apiBase(ctx);
        final String model = Prefs.apiModel(ctx);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                LlmResult out;
                try {
                    String raw = chatOnce(base, key, model,
                            SYS_CHAT + "\n\n账本摘要：\n" + digest + "\n\n用户：" + question, false);
                    out = LlmResult.text(raw.trim());
                } catch (Exception e) {
                    out = LlmResult.text(LocalCopy.chat(question, digest));
                }
                deliver(cb, out);
            }
        });
    }

    /** 连通性测试：设置页用 */
    public static void ping(final Context ctx, final Callback cb) {
        final String key = Prefs.apiKey(ctx);
        if (key.isEmpty()) {
            deliver(cb, LlmResult.fail("还没有填 API Key"));
            return;
        }
        final String base = Prefs.apiBase(ctx);
        final String model = Prefs.apiModel(ctx);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String raw = chatOnce(base, key, model, "只回复两个字：可用", false);
                    deliver(cb, LlmResult.text(raw.trim()));
                } catch (Exception e) {
                    deliver(cb, LlmResult.fail(e.getMessage() == null ? "连接失败" : e.getMessage()));
                }
            }
        });
    }

    private static void deliver(final Callback cb, final LlmResult r) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r);
            }
        });
    }

    /* ---------------- HTTP ---------------- */

    private static String chatOnce(String base, String key, String model, String prompt, boolean json) throws Exception {
        URL url = new URL(base.replaceAll("/+$", "") + "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", json ? 0.1 : 0.7);
        body.put("max_tokens", json ? 300 : 700);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        body.put("messages", msgs);
        if (json) {
            body.put("response_format", new JSONObject().put("type", "json_object"));
        }

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + shortOf(sb.toString()));
        }

        JSONObject obj = new JSONObject(sb.toString());
        JSONArray choices = obj.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("模型没有返回内容");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) throw new IllegalStateException("返回格式不对");
        return msg.optString("content", "");
    }

    private static String shortOf(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /* ---------------- 解析模型 JSON ---------------- */

    static LlmResult parseRecordJson(String raw) {
        try {
            String s = raw.trim();
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a < 0 || b <= a) return null;
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            LlmResult r = new LlmResult();
            r.ok = true;
            r.type = o.optInt("type", 0) == 1 ? 1 : 0;
            r.amount = o.optDouble("amount", 0);
            if (r.amount <= 0) return null;
            String cat = o.optString("category", "other");
            Category c = Category.byCode(cat);
            r.category = c.type == r.type ? c.code : (r.type == 1 ? "parttime" : "other");
            r.note = o.optString("note", "");
            r.date = o.optString("date", DateUtil.today());
            r.slot = o.optString("slot", "午");
            r.time = r.slot.equals("早") ? "08:00" : r.slot.equals("午") ? "12:10" : r.slot.equals("晚") ? "18:30" : "21:15";
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}
