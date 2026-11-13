package com.wool.ledger.util;

import android.graphics.Bitmap;

import com.wool.ledger.view.TimetableView;

/**
 * 跨页面传「正在拖的那一笔」：这一格 → 课表 → 回来。
 *
 * 系统 drag & drop 的会话绑在发起它的窗口上，源 Activity finish 后拖放会一起结束，
 * 「这一格」不能真回退到课表继续拖。所以进入这一格时先截一张课表页快照，
 * 长按拖动时在这一格里还原那张课表（快照 + 目标格高亮），松手后落库再 finish，
 * 视觉上就是「回到课表继续拖」。
 */
public class DragBus {

    public static long billId = 0;
    public static String returnDate = null;
    public static String returnSlot = null;

    /** 进入「这一格」时抓的课表页整窗截图 */
    public static Bitmap snapshot = null;
    /** 课表 View，用来把手指坐标换算成日期/时段 */
    private static java.lang.ref.WeakReference<TimetableView> tableRef = null;

    public static void put(long id, String date, String slot) {
        billId = id;
        returnDate = date;
        returnSlot = slot;
    }

    public static boolean active() {
        return billId > 0;
    }

    public static void clear() {
        billId = 0;
        returnDate = null;
        returnSlot = null;
    }

    public static void setTable(TimetableView table) {
        tableRef = table == null ? null : new java.lang.ref.WeakReference<>(table);
    }

    public static TimetableView table() {
        return tableRef == null ? null : tableRef.get();
    }

    public static void setSnapshot(Bitmap bmp) {
        // 不在这里 recycle 旧图：PixelCopy 晚到时旧图可能正被「这一格」的拖拽层使用
        snapshot = bmp;
    }

    /** 只释放快照；课表引用由 HomeFragment.onDestroyView / setTable(null) 管（不要在这里清，否第二次拖就找不到课表） */
    public static void clearSnapshot() {
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
        }
    }
}
