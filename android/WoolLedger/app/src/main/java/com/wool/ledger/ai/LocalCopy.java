package com.wool.ledger.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 没有 API Key 时的本地文案。不是写死的演示稿：
 * 从调用方给的账本摘要里取真实数字（总额、分类占比），再拼成人话。
 */
public class LocalCopy {

    private static final Pattern TOTAL = Pattern.compile("支出\\s*([0-9,]+(?:\\.[0-9]+)?)\\s*元");

    /** 摘要形如：近 7 天支出 280.50 元。分类：餐饮 41%、学习 16%。…… */
    public static String report(String digest) {
        String total = "0.00";
        List<String[]> cats = new ArrayList<>();

        if (digest != null) {
            Matcher m = TOTAL.matcher(digest);
            if (m.find()) total = m.group(1);
            int i = digest.indexOf("分类：");
            if (i >= 0) {
                String seg = digest.substring(i + 3);
                int end = seg.indexOf('。');
                if (end > 0) seg = seg.substring(0, end);
                for (String part : seg.split("、")) {
                    String s = part.trim();
                    int sp = s.lastIndexOf(' ');
                    if (sp > 0) cats.add(new String[]{s.substring(0, sp), s.substring(sp + 1)});
                }
            }
        }

        String top1 = cats.size() > 0 ? cats.get(0)[0] : "餐饮";
        String top1p = cats.size() > 0 ? cats.get(0)[1] : "";
        String top2 = cats.size() > 1 ? cats.get(1)[0] : "";
        String top2p = cats.size() > 1 ? cats.get(1)[1] : "";

        StringBuilder sb = new StringBuilder();
        sb.append("这段时间一共花了 ").append(total).append(" 元。");
        if (!top1.isEmpty()) {
            sb.append("钱主要花在").append(top1);
            if (!top1p.isEmpty()) sb.append("（").append(top1p).append("）");
            sb.append("，");
        }
        if (!top2.isEmpty()) {
            sb.append("其次是").append(top2);
            if (!top2p.isEmpty()) sb.append("（").append(top2p).append("）");
            sb.append("。");
        } else {
            sb.append("没有别的分类了。");
        }

        sb.append("\n");
        sb.append("1. 给「").append(top1).append("」设个月度上限，超了就停一周。\n");
        if (!top2.isEmpty()) {
            sb.append("2. 「").append(top2).append("」这种非刚需，买之前放购物车过一晚。\n");
        } else {
            sb.append("2. 单笔超过 50 的先记下来，回头看会发现很多是冲动消费。\n");
        }
        sb.append("3. 每周日翻一次这周的账，比月底一次性复盘更容易改。\n");
        sb.append("4. 想省得更狠，只动占比最高的那一类，别到处抠。");
        return sb.toString();
    }

    public static String chat(String question, String digest) {
        String q = question == null ? "" : question;
        String total = "0.00";
        if (digest != null) {
            Matcher m = TOTAL.matcher(digest);
            if (m.find()) total = m.group(1);
        }
        if (q.contains("还能花") || q.contains("剩") || q.contains("预算")) {
            return "这段时间已经花了 " + total + " 元。想知道还能花多少，得先看你在「我的」里设的月度预算和账期。";
        }
        if (q.contains("夜宵") || q.contains("奶茶")) {
            return "夜宵和奶茶最容易失控，建议只记额度不记内容，月底看总数。";
        }
        if (q.contains("保密") || q.contains("隐私")) {
            return "好。这类账我只记金额、分类和时段，备注不保存。";
        }
        return "我在。说一句账我来记，也可以问「这个月还能花多少」。";
    }
}
