package com.wool.ledger.data;

import android.content.Context;

import com.wool.ledger.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/** 首次启动灌入演示数据；日期相对今天，保证课表当周有格子。 */
public class Seed {

    public static void maybeSeed(Context ctx) {
        BillDao dao = new BillDao(ctx);
        if (Prefs.seeded(ctx) || !dao.isEmpty()) return;
        for (Bill b : demo()) dao.insert(b);
        Prefs.markSeeded(ctx);
    }

    /** k = 距今几天（0 = 今天） */
    private static String d(int k) {
        return DateUtil.addDays(DateUtil.today(), -k);
    }

    public static List<Bill> demo() {
        List<Bill> out = new ArrayList<>();
        out.add(b(0, "08:20", 9.5, "food", "咖啡 · 自习室"));
        out.add(b(0, "12:10", 15, "food", "食堂 · 二荤一素"));
        out.add(b(0, "16:12", 45.5, "study", "淘宝 · 一本教材"));
        out.add(b(1, "12:20", 18, "food", "食堂 · 麻辣烫"));
        out.add(b(1, "19:30", 38, "fun", "电影 · 学生票"));
        out.add(b(2, "12:15", 15, "food", "食堂 · 黄焖鸡"));
        out.add(b(2, "21:10", 12, "food", "奶茶 · 三分糖"));
        out.add(b(3, "07:40", 6, "food", "包子豆浆"));
        out.add(b(3, "18:50", 43.5, "daily", "超市 · 洗发水"));
        out.add(b(4, "12:05", 12, "food", "食堂 · 一荤一素"));
        out.add(b(4, "21:30", 28, "food", "夜宵 · 烧烤"));
        out.add(b(5, "17:20", 23, "shop", "水果拼盘"));
        out.add(b(6, "10:05", 200, "parttime", "家教 · 高二数学"));
        out.add(b(7, "12:30", 14, "food", "食堂 · 面食"));
        out.add(b(8, "20:00", 6, "study", "打印 · 实验报告"));
        return out;
    }

    private static Bill b(int daysAgo, String time, double amount, String cat, String note) {
        Bill b = new Bill();
        b.billDate = d(daysAgo);
        b.billTime = time;
        b.amount = amount;
        b.category = cat;
        b.note = note;
        b.type = "parttime".equals(cat) ? Bill.INCOME : Bill.EXPENSE;
        b.slot = DateUtil.slotOf(time);
        b.createTime = b.billDate + " " + time;
        b.source = 0;
        return b;
    }
}
