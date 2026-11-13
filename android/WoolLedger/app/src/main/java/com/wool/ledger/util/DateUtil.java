package com.wool.ledger.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class DateUtil {

    public static final String[] WD = {"一", "二", "三", "四", "五", "六", "日"};
    public static final String[] SLOTS = {"早", "午", "晚", "夜"};

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    public static String nowTime() {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
    }

    public static String nowFull() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date());
    }

    public static String monthKey(String date) {
        return date == null || date.length() < 7 ? "" : date.substring(0, 7);
    }

    public static String thisMonth() {
        return new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(new Date());
    }

    /** 0 = 周一 … 6 = 周日 */
    public static int weekdayIndex(String date) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date));
            int d = c.get(Calendar.DAY_OF_WEEK);   // 1 = 周日
            return (d + 5) % 7;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String weekdayCn(String date) {
        return WD[weekdayIndex(date)];
    }

    public static String monthDay(String date) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date));
            return (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日";
        } catch (Exception e) {
            return date;
        }
    }

    public static String shortDate(String date) {
        if (date == null || date.length() < 10) return date;
        int m = Integer.parseInt(date.substring(5, 7));
        int d = Integer.parseInt(date.substring(8, 10));
        return m + " 月 " + d + " 日";
    }

    public static String slotOf(String time) {
        int h = 12;
        try {
            h = Integer.parseInt(time.substring(0, 2));
        } catch (Exception ignored) {
        }
        if (h < 10) return "早";
        if (h < 15) return "午";
        if (h < 20) return "晚";
        return "夜";
    }

    public static String addDays(String date, int delta) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date));
            c.add(Calendar.DAY_OF_MONTH, delta);
            return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.getTime());
        } catch (Exception e) {
            return date;
        }
    }

    public static int daysInMonth(String ym) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new SimpleDateFormat("yyyy-MM", Locale.CHINA).parse(ym));
            return c.getActualMaximum(Calendar.DAY_OF_MONTH);
        } catch (Exception e) {
            return 30;
        }
    }
}
