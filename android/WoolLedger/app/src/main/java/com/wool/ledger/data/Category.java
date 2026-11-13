package com.wool.ledger.data;

import java.util.ArrayList;
import java.util.List;

/** 分类常量表：code / 中文名 / 颜色（hex）/ 类型。与原型 CATS 一一对应。 */
public class Category {

    public final String code;
    public final String name;
    public final int color;
    public final int type;   // 0 支出 1 收入

    public Category(String code, String name, int color, int type) {
        this.code = code;
        this.name = name;
        this.color = color;
        this.type = type;
    }

    public static final List<Category> ALL = new ArrayList<>();

    static {
        // 支出 9 类
        ALL.add(new Category("food",      "餐饮", 0xFFE8A15C, 0));
        ALL.add(new Category("transport", "交通", 0xFF8FB8D8, 0));
        ALL.add(new Category("shop",      "购物", 0xFF6FA8DC, 0));
        ALL.add(new Category("study",     "学习", 0xFFA98BD1, 0));
        ALL.add(new Category("fun",       "娱乐", 0xFFD98C7A, 0));
        ALL.add(new Category("daily",     "日用", 0xFFB9C48C, 0));
        ALL.add(new Category("stay",      "住宿", 0xFFC8A98B, 0));
        ALL.add(new Category("medical",   "医疗", 0xFFD98CA8, 0));
        ALL.add(new Category("other",     "其他", 0xFF9AA0A6, 0));
        // 收入分类
        ALL.add(new Category("income_other", "其他收入", 0xFF4FB286, 1));
        // 原有收入分类
        ALL.add(new Category("parttime",  "兼职", 0xFF4FB286, 1));
        ALL.add(new Category("prize",     "奖学金", 0xFF63B7A8, 1));
        ALL.add(new Category("gift",      "红包", 0xFFD9A05B, 1));
        ALL.add(new Category("family",    "家里", 0xFF8FBF7F, 1));
        ALL.add(new Category("invest",    "理财", 0xFF6FA89A, 1));
    }

    public static Category byCode(String code) {
        for (Category c : ALL) {
            if (c.code.equals(code)) return c;
        }
        return byCode("other");
    }

    public static List<Category> expense() {
        List<Category> out = new ArrayList<>();
        for (Category c : ALL) if (c.type == 0) out.add(c);
        return out;
    }

    public static List<Category> income() {
        List<Category> out = new ArrayList<>();
        for (Category c : ALL) if (c.type == 1) out.add(c);
        return out;
    }

    public static String nameOf(String code) {
        return byCode(code).name;
    }
}
