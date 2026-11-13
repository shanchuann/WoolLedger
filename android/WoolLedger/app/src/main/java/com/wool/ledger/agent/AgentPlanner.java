package com.wool.ledger.agent;

import com.wool.ledger.ai.*;
import com.wool.ledger.data.*;
import com.wool.ledger.util.DateUtil;

import org.json.*;

import java.util.*;
import java.util.regex.*;

/** Offline intent router; online planning uses the same allowlisted JSON contract. */
public final class AgentPlanner {
    public static JSONObject local(String q) throws JSONException {
        JSONObject o = new JSONObject();
        String action = "help";
        if (q.matches(".*(待确认|通知记录|自动记账).*")) action = "inbox";
        else if (q.matches(".*(对账|核对|重复|队长).*")) action = "reconcile";
        else if (q.matches(".*(撤销|取消操作).*")) action = "undo_hint";
        else if (q.matches(".*(预算|还能花|还可以花).*")) {
            Matcher m =
                    Pattern.compile(
                                    "(?:预算)(?:设为|改为|调整为|设置为|设置成|改成|调整到|设成|为|到|是)?\\s*[¥￥]?([0-9]+(?:\\.[0-9]{1,2})?)")
                            .matcher(q);
            if (m.find()) {
                o.put("budget", Double.parseDouble(m.group(1)));
                action = "budget";
            } else action = "budget_query";
        } else if (q.matches(".*(删除|删掉|移除).*")) {
            action = "delete";
            o.put("id", id(q));
        } else if (q.matches(".*(改为|改成|修改|改到|改一下).*")) {
            action = "update";
            o.put("id", id(q));
            Matcher m =
                    Pattern.compile("(?:改为|改成|金额改为|金额改成)\\s*[¥￥]?([0-9]+(?:\\.[0-9]{1,2})?)")
                            .matcher(q);
            if (m.find()) o.put("amount", Double.parseDouble(m.group(1)));
            Matcher dateChange = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(q);
            if (dateChange.find()) o.put("date", dateChange.group());
            Matcher noteChange = Pattern.compile("备注(?:改为|改成|设为|设置为)[「“\"]?([^」”\"]+)").matcher(q);
            if (noteChange.find()) o.put("note", noteChange.group(1).trim());
            for (Category c : Category.ALL) if (q.contains(c.name)) o.put("category", c.code);
        } else if (q.matches(".*(查|多少|几笔|汇总|统计|报告|总结|账单|列出|最近).*")) action = "query";
        else {
            JSONArray entries = new JSONArray();
            String[] parts = q.split("[，,、;；\\n]");
            for (String part : parts) {
                String amountText =
                        OfflineParser.normalizeNumbers(part)
                                .replaceAll("\\d{4}-\\d{2}-\\d{2}", "")
                                .replaceAll("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?", "")
                                .replaceAll("\\d{1,2}\\s*[日号]", "");
                if (!part.matches(
                                ".*(记|饭|餐|咖啡|奶茶|地铁|公交|打车|元|块|收入|支出|买|消费|付|收|工资|兼职|红包|生活费|超市|电费|水费|房租|药|购物|打印|电影|豆浆).*")
                        || !amountText.matches(".*[0-9].*")) {
                    entries = new JSONArray();
                    break;
                }
                LlmResult r = OfflineParser.parse(amountText);
                r.note = part;
                String[] dateRange = range(part);
                if (part.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) r.date = dateRange[0];
                else {
                    LlmResult dated = OfflineParser.parse(part);
                    r.date = dated.date;
                    r.time = dated.time;
                    r.slot = dated.slot;
                }
                if (!r.ok) {
                    entries = new JSONArray();
                    break;
                }
                Bill b = new Bill();
                b.amount = r.amount;
                b.category = r.category;
                b.type = r.type;
                b.note = r.note;
                b.billDate = r.date;
                b.billTime = r.time;
                b.slot = r.slot;
                b.createTime = DateUtil.nowFull();
                b.source = 1;
                entries.put(LedgerCodec.json(b));
            }
            if (entries.length() > 0) {
                action = "create";
                o.put("entries", entries);
            }
        }
        if ("query".equals(action) && id(q) > 0) o.put("id", id(q));
        o.put("action", action);
        String[] range = range(q);
        o.put("start", range[0]).put("end", range[1]);
        if (q.contains("收入")) o.put("type", 1);
        else if (q.contains("支出")) o.put("type", 0);
        if ("query".equals(action))
            for (Category c : Category.ALL)
                if (q.contains(c.name)) {
                    o.put("category", c.code);
                    break;
                }
        Matcher kw = Pattern.compile("[「“\"]([^」”\"]+)[」”\"]").matcher(q);
        if (kw.find()) o.put("keyword", kw.group(1));
        if ("reconcile".equals(action)) {
            Matcher total =
                    Pattern.compile("(?:合计|总额|账单总额)\\s*[¥￥]?([0-9]+(?:\\.[0-9]{1,2})?)").matcher(q);
            if (total.find()) o.put("expected", Double.parseDouble(total.group(1)));
        }
        return o;
    }

    private static long id(String q) {
        Matcher m = Pattern.compile("(?:#|编号|第)\\s*(\\d+)").matcher(q);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    public static String[] range(String q) {
        String today = DateUtil.today(), ym = DateUtil.thisMonth();
        String start = ym + "-01", end = ym + "-" + DateUtil.daysInMonth(ym);
        if (q.contains("全部") || q.contains("所有")) {
            start = "1970-01-01";
            end = "2199-12-31";
        } else if (q.contains("昨天")) {
            start = end = DateUtil.addDays(today, -1);
        } else if (q.contains("今天")) {
            start = end = today;
        } else if (q.contains("近7天") || q.contains("最近七天")) {
            start = DateUtil.addDays(today, -6);
            end = today;
        } else if (q.contains("上周") || q.contains("本周") || q.contains("这周")) {
            start =
                    DateUtil.addDays(
                            today, -DateUtil.weekdayIndex(today) - (q.contains("上周") ? 7 : 0));
            end = DateUtil.addDays(start, 6);
        } else if (q.contains("上个月") || q.contains("上月")) {
            String prev = DateUtil.addDays(ym + "-01", -1).substring(0, 7);
            start = prev + "-01";
            end = prev + "-" + DateUtil.daysInMonth(prev);
        }
        Matcher recent = Pattern.compile("(?:近|最近)([0-9]{1,3})天").matcher(q);
        if (recent.find()) {
            int days = Integer.parseInt(recent.group(1));
            if (days > 0) {
                start = DateUtil.addDays(today, 1 - days);
                end = today;
            }
        }
        if (q.contains("今年") || q.contains("去年")) {
            int year = Integer.parseInt(ym.substring(0, 4)) - (q.contains("去年") ? 1 : 0);
            start = year + "-01-01";
            end = year + "-12-31";
        }
        Matcher month = Pattern.compile("(?:(20[0-9]{2})年)?([0-9]{1,2})月").matcher(q);
        if (month.find()) {
            int m = Integer.parseInt(month.group(2));
            if (m >= 1 && m <= 12) {
                String target =
                        (month.group(1) == null ? ym.substring(0, 4) : month.group(1))
                                + "-"
                                + String.format(Locale.ROOT, "%02d", m);
                start = target + "-01";
                end = target + "-" + DateUtil.daysInMonth(target);
            }
        }
        Matcher explicit = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(q);
        if (explicit.find()) {
            start = end = explicit.group();
            if (explicit.find()) end = explicit.group();
        }
        return new String[] {start, end};
    }
}
