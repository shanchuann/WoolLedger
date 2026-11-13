package com.wool.ledger.ai;

import com.wool.ledger.data.Category;
import com.wool.ledger.util.DateUtil;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 断网 / 未配置 Key 时的本地规则解析：金额 + 分类关键字 + 日期 + 时段。 */
public class OfflineParser {

    private static final Pattern NUM = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)");

    private static final Pattern CN_NUM = Pattern.compile("[零〇一二两三四五六七八九十百千万亿]+");

    private static final String[][] CAT_WORDS = {
            {"food", "吃 饭 食堂 外卖 咖啡 奶茶 烧烤 夜宵 早餐 午饭 晚饭 餐 快餐 面 粥 火锅 麻辣烫 包子 米线 汉堡 炸鸡 水果 零食"},
            {"transport", "打车 地铁 公交 高铁 火车 机票 加油 停车 共享单车 滴滴"},
            {"shop", "淘宝 京东 拼多多 买 网购 超市 商城 衣服 鞋 包 化妆品"},
            {"study", "教材 书 打印 复印 文具 网课 课程 考试 报名 培训 学费"},
            {"fun", "电影 游戏 会员 KTV 演出 门票 剧本杀 桌游 旅游 演唱会"},
            {"daily", "洗发水 纸巾 牙膏 洗衣 日用 生活用品 电费 水费 话费 流量"},
            {"stay", "房租 住宿 水电 宿舍 酒店"},
            {"medical", "药 医院 挂号 体检 医疗 看病 牙"},
            {"parttime", "兼职 家教 打工 实习 工资 收入 赚 接单"},
            {"prize", "奖学金"},
            {"gift", "红包 礼金 转账收"},
            {"family", "生活费 家里 爸妈 打钱"}
    };

    private static final String INCOME_HINT = "收入 赚 工资 兼职 家教 奖学金 红包 生活费 退 报销 收";

    /** 一句话多笔：「早饭 6，午饭 15」按标点拆开逐条解析。 */
    public static java.util.List<LlmResult> parseMulti(String raw) {
        java.util.List<LlmResult> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        String[] parts = raw.split("[，,、;；\\n]");
        for (String part : parts) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            LlmResult r = parse(s);
            if (r.ok && r.amount > 0) out.add(r);
        }
        return out;
    }

    /**
     * 把中文数字归一成阿拉伯数字，专治语音识别输出的「十五块」「一百二」。
     * 只做保守转换：两个字以上的数字串、或后面紧跟 元/块/毛/角/钱/日/号/月 的单字；
     * 「一起」「一直」「一点」这类不会被误伤。
     */
    public static String normalizeNumbers(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = CN_NUM.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String run = m.group();
            char next = m.end() < text.length() ? text.charAt(m.end()) : 0;
            if (!shouldConvertCn(run, next, text)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(run));
            } else {
                m.appendReplacement(sb, String.valueOf(cnToLong(run)));
            }
        }
        m.appendTail(sb);
        String s = sb.toString();
        // 「12块5」「12元5角」→ 12.5；「五毛」→ 0.5（归一后是 5毛）
        s = s.replaceAll("(\\d+)\\s*(?:块|元)\\s*(\\d)\\s*(?:毛|角)?", "$1.$2");
        s = s.replaceAll("(?<![\\d.])(\\d)\\s*(?:毛|角)", "0.$1");
        return s;
    }

    private static boolean shouldConvertCn(String run, char next, String text) {
        if (run.isEmpty()) return false;
        if ("万一".equals(run) || "千万".equals(run)) return false;   // 口语词，不是金额
        if (run.length() >= 2) return true;
        char c = run.charAt(0);
        if (c == '十') return true;            // 十块
        if (c == '零' || c == '〇') return false;
        String units = "元块毛角钱日号月";
        if (next != 0 && units.indexOf(next) >= 0) return true;   // 五块 / 三号
        // 句尾单字（「午饭六」）只有出现记账语境时才转，避免「统一」这类词被误转
        return next == 0 && hasLedgerContext(text);
    }

    /** 文本里出现分类关键字或钱的单位，才把句尾单个汉字数字当金额 */
    private static boolean hasLedgerContext(String text) {
        if (text == null) return false;
        if (text.indexOf('元') >= 0 || text.indexOf('块') >= 0 || text.indexOf('花') >= 0
                || text.indexOf('买') >= 0 || text.indexOf('付') >= 0 || text.indexOf('钱') >= 0) {
            return true;
        }
        for (String[] pair : CAT_WORDS) {
            for (String word : pair[1].split(" ")) {
                if (!word.isEmpty() && text.contains(word)) return true;
            }
        }
        return false;
    }

    private static int cnDigit(char c) {
        switch (c) {
            case '零': case '〇': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    private static long cnUnit(char c) {
        switch (c) {
            case '十': return 10;
            case '百': return 100;
            case '千': return 1000;
            case '万': return 10000;
            case '亿': return 100000000L;
            default: return 0;
        }
    }

    private static long cnToLong(String s) {
        long total = 0, section = 0, number = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = cnDigit(c);
            if (d >= 0) {
                number = d;
                continue;
            }
            long u = cnUnit(c);
            if (u == 10000 || u == 100000000L) {
                section = (section + number) * u;
                total += section;
                section = 0;
                number = 0;
            } else if (u > 0) {
                if (number == 0) number = 1;
                section += number * u;
                number = 0;
            }
        }
        long value = total + section + number;
        // 口语省字：一百二=120、两千五=2500、一千二=1200
        char last = s.charAt(s.length() - 1);
        int lastD = cnDigit(last);
        if (s.length() >= 2 && lastD > 0 && s.indexOf('十') < 0) {
            String head = s.substring(0, s.length() - 1);
            if (head.endsWith("百")) return cnToLong(head) + lastD * 10L;
            if (head.endsWith("千")) return cnToLong(head) + lastD * 100L;
        }
        return value;
    }

    public static LlmResult parse(String raw) {
        LlmResult r = new LlmResult();
        r.ok = true;
        r.offline = true;
        r.date = DateUtil.today();
        r.time = DateUtil.nowTime();

        String text = raw == null ? "" : raw.trim();
        r.note = text;
        // 语音/本地识别经常把数字写成汉字（「午饭十五块」），先归一成阿拉伯数字再跑正则；
        // 备注仍然保存用户原话
        String norm = normalizeNumbers(text);

        // 日期
        if (text.contains("前天")) {
            r.date = DateUtil.addDays(DateUtil.today(), -2);
        } else if (text.contains("昨天")) {
            r.date = DateUtil.addDays(DateUtil.today(), -1);
        } else if (text.contains("明天")) {
            r.date = DateUtil.addDays(DateUtil.today(), 1);
        } else {
            Matcher m = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]").matcher(norm);
            if (m.find()) {
                String ym = DateUtil.thisMonth();
                r.date = ym + "-" + String.format("%02d", Integer.parseInt(m.group(2)));
            } else {
                Matcher d = Pattern.compile("(\\d{1,2})\\s*[日号]").matcher(norm);
                if (d.find()) {
                    r.date = DateUtil.thisMonth() + "-" + String.format("%02d", Integer.parseInt(d.group(1)));
                }
            }
        }

        // 时段
        String slot = null;
        if (text.contains("早") || text.contains("早餐")) slot = "早";
        else if (text.contains("午") || text.contains("中午") || text.contains("午饭")) slot = "午";
        else if (text.contains("晚") || text.contains("晚饭") || text.contains("傍晚")) slot = "晚";
        else if (text.contains("夜") || text.contains("宵")) slot = "夜";
        if (slot != null) {
            r.slot = slot;
            r.time = slot.equals("早") ? "08:00" : slot.equals("午") ? "12:10" : slot.equals("晚") ? "18:30" : "21:15";
        } else {
            r.slot = DateUtil.slotOf(r.time);
        }

        // 金额：优先带"元/块"的数字，其次最后一个数字
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块|块钱|rmb|RMB)").matcher(norm);
        if (m.find()) {
            r.amount = Double.parseDouble(m.group(1));
        } else {
            Matcher all = NUM.matcher(norm);
            double last = 0;
            while (all.find()) {
                String g = all.group(1);
                if (g.length() <= 6) last = Double.parseDouble(g);
            }
            r.amount = last;
        }
        if (r.amount <= 0) {
            r.ok = false;
            r.error = "没听出金额，再说一次？比如「午饭食堂 15」";
            return r;
        }

        // 类型
        boolean income = false;
        for (String w : INCOME_HINT.split(" ")) {
            if (!w.isEmpty() && text.contains(w)) {
                income = true;
                break;
            }
        }
        r.type = income ? 1 : 0;

        // 分类
        String best = income ? "parttime" : "other";
        int bestLen = 0;
        for (String[] pair : CAT_WORDS) {
            for (String word : pair[1].split(" ")) {
                if (word.isEmpty()) continue;
                if (text.contains(word)) {
                    Category c = Category.byCode(pair[0]);
                    boolean sameType = (c.type == 1) == income;
                    if (sameType && word.length() > bestLen) {
                        bestLen = word.length();
                        best = pair[0];
                    }
                }
            }
        }
        r.category = best;
        return r;
    }
}
