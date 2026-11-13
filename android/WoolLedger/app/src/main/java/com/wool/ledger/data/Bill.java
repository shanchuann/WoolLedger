package com.wool.ledger.data;

/** 一条账目。字段与预览页一致：类型 / 金额 / 分类 / 备注 / 日期 / 时段。 */
public class Bill {

    public static final int EXPENSE = 0;
    public static final int INCOME = 1;

    public long id;
    public int type = EXPENSE;
    public double amount;
    public String category = "food";
    public String note = "";
    /** yyyy-MM-dd */
    public String billDate;
    /** HH:mm */
    public String billTime = "12:00";
    /** 早 / 午 / 晚 / 夜 */
    public String slot = "午";
    public String createTime;
    /** 0 手动 1 AI 解析 2 语音录入 */
    public int source = 0;

    public Bill() {
    }

    public boolean isIncome() {
        return type == INCOME;
    }

    public int categoryColor() {
        return Category.byCode(category).color;
    }

    public String categoryName() {
        return Category.byCode(category).name;
    }

    /** 百分比 tooltip 用 */
    public Bill copy() {
        Bill b = new Bill();
        b.id = id;
        b.type = type;
        b.amount = amount;
        b.category = category;
        b.note = note;
        b.billDate = billDate;
        b.billTime = billTime;
        b.slot = slot;
        b.createTime = createTime;
        b.source = source;
        return b;
    }
}
