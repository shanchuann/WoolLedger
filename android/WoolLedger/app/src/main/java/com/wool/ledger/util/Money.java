package com.wool.ledger.util;

import java.util.Locale;

public class Money {

    public static String fmt(double v) {
        return String.format(Locale.CHINA, "%,.2f", v);
    }

    /** 不带千分位，用于小票等宽排版 */
    public static String plain(double v) {
        return String.format(Locale.CHINA, "%.2f", v);
    }

    /** 课表格子里的短金额：整数省小数，其他最多两位 */
    public static String shortFmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.005) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.CHINA, "%.2f", v);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
