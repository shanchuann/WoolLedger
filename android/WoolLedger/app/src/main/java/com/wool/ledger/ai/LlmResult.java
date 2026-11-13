package com.wool.ledger.ai;

/** AI 的一次返回：结构化记账结果 或 一段文字（报告 / 问答）。 */
public class LlmResult {

    public boolean ok;
    /** true 表示走的是本地兜底解析（没配 Key / 没网 / 解析失败） */
    public boolean offline;

    // 记账字段
    public int type = 0;              // 0 支出 1 收入
    public double amount;
    public String category = "food";
    public String note = "";
    public String date;               // yyyy-MM-dd
    public String time;               // HH:mm
    public String slot = "午";

    // 文本结果
    public String text = "";
    public String error = "";

    public static LlmResult fail(String msg) {
        LlmResult r = new LlmResult();
        r.ok = false;
        r.error = msg;
        return r;
    }

    public static LlmResult text(String t) {
        LlmResult r = new LlmResult();
        r.ok = true;
        r.text = t;
        return r;
    }
}
