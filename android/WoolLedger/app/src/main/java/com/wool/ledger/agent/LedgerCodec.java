package com.wool.ledger.agent;

import com.wool.ledger.data.*;
import com.wool.ledger.util.DateUtil;

import org.json.*;

import java.text.*;
import java.util.*;

public final class LedgerCodec {
    public static JSONObject json(Bill b) throws JSONException {
        return new JSONObject()
                .put("id", b.id)
                .put("type", b.type)
                .put("amount", b.amount)
                .put("category", b.category)
                .put("note", b.note)
                .put("date", b.billDate)
                .put("time", b.billTime)
                .put("slot", b.slot)
                .put("created", b.createTime)
                .put("source", b.source);
    }

    public static Bill bill(JSONObject o) {
        Bill b = new Bill();
        b.id = o.optLong("id");
        b.type = o.optInt("type");
        b.amount = o.optDouble("amount", 0);
        b.category = o.optString("category", b.type == 1 ? "income_other" : "other");
        b.note = o.optString("note", "");
        b.billDate = o.optString("date", DateUtil.today());
        b.billTime = o.optString("time", DateUtil.nowTime());
        b.slot = o.optString("slot", DateUtil.slotOf(b.billTime));
        b.createTime = o.optString("created", DateUtil.nowFull());
        b.source = o.optInt("source", 1);
        return b;
    }

    public static boolean date(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            f.setLenient(false);
            return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}") && f.format(f.parse(s)).equals(s);
        } catch (Exception e) {
            return false;
        }
    }

    public static void validate(Bill b) {
        if (!Double.isFinite(b.amount)
                || b.amount <= 0
                || b.amount > 10000000
                || Math.abs(b.amount * 100 - Math.rint(b.amount * 100)) > 0.0001)
            throw new IllegalArgumentException("金额须为正数，最多两位小数且不超过一千万元");
        if (!date(b.billDate) || !b.billTime.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))
            throw new IllegalArgumentException("日期或时间无效");
        if (b.note.length() > 500) throw new IllegalArgumentException("备注不能超过 500 字");
        if ((b.type != 0 && b.type != 1)
                || !Category.byCode(b.category).code.equals(b.category)
                || Category.byCode(b.category).type != b.type)
            throw new IllegalArgumentException("收支类型和分类不匹配");
        if (!Arrays.asList(DateUtil.SLOTS).contains(b.slot))
            throw new IllegalArgumentException("时段无效");
    }

    public static String summary(Bill b) {
        return "#"
                + b.id
                + " "
                + b.billDate
                + " "
                + b.billTime
                + " "
                + (b.type == 1 ? "收入" : "支出")
                + " ¥"
                + com.wool.ledger.util.Money.fmt(b.amount)
                + " · "
                + Category.nameOf(b.category)
                + " · "
                + b.note;
    }
}
