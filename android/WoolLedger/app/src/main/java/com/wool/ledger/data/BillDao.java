package com.wool.ledger.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** bill 表读写 + 统计聚合。所有方法都是同步的，调用方负责放到后台线程。 */
public class BillDao {

    private final DbHelper helper;

    public BillDao(Context ctx) {
        this.helper = DbHelper.get(ctx);
    }

    private static Bill from(Cursor c) {
        Bill b = new Bill();
        // 读取时再校一次类型：分类是收入类就一定按收入显示（+）
        // 具体在方法末尾统一处理
        b.id = c.getLong(c.getColumnIndexOrThrow("id"));
        b.type = c.getInt(c.getColumnIndexOrThrow("type"));
        b.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        b.category = c.getString(c.getColumnIndexOrThrow("category"));
        b.note = c.getString(c.getColumnIndexOrThrow("note"));
        b.billDate = c.getString(c.getColumnIndexOrThrow("bill_date"));
        b.billTime = c.getString(c.getColumnIndexOrThrow("bill_time"));
        b.slot = c.getString(c.getColumnIndexOrThrow("slot"));
        b.createTime = c.getString(c.getColumnIndexOrThrow("create_time"));
        b.source = c.getInt(c.getColumnIndexOrThrow("source"));
        int want = Category.byCode(b.category).type;
        if (b.type != want) b.type = want;
        return b;
    }

    /** 分类与类型对齐：收入类分类强制 type=1，支出类强制 type=0。 */
    private void normalize(Bill b) {
        Category c = Category.byCode(b.category);
        b.type = c.type;
    }

    private ContentValues values(Bill b) {
        normalize(b);
        ContentValues v = new ContentValues();
        v.put("type", b.type);
        v.put("amount", b.amount);
        v.put("category", b.category);
        v.put("note", b.note == null ? "" : b.note);
        v.put("bill_date", b.billDate);
        v.put("bill_time", b.billTime);
        v.put("slot", b.slot);
        v.put("create_time", b.createTime);
        v.put("source", b.source);
        return v;
    }

    public long insert(Bill b) {
        long id = helper.getWritableDatabase().insert("bill", null, values(b));
        if (id < 0) throw new IllegalStateException("账目保存失败");
        b.id = id;
        return id;
    }

    public void update(Bill b) {
        normalize(b);
        helper.getWritableDatabase().update("bill", values(b), "id=?", new String[]{String.valueOf(b.id)});
    }

    public void delete(long id) {
        helper.getWritableDatabase().delete("bill", "id=?", new String[]{String.valueOf(id)});
    }

    public Bill get(long id) {
        Cursor c = helper.getReadableDatabase().query("bill", null, "id=?", new String[]{String.valueOf(id)}, null, null, null);
        try {
            return c.moveToFirst() ? from(c) : null;
        } finally {
            c.close();
        }
    }

    private List<Bill> query(String where, String[] args) {
        List<Bill> out = new ArrayList<>();
        Cursor c = helper.getReadableDatabase().query("bill", null, where, args, null, null,
                "bill_date DESC, bill_time DESC, id DESC");
        try {
            while (c.moveToNext()) out.add(from(c));
        } finally {
            c.close();
        }
        return out;
    }

    public List<Bill> listAll() {
        return query(null, null);
    }

    /** ym = yyyy-MM */
    public List<Bill> listMonth(String ym) {
        return query("bill_date LIKE ?", new String[]{ym + "%"});
    }

    public List<Bill> listDate(String date) {
        return query("bill_date=?", new String[]{date});
    }

    public List<Bill> listDateSlot(String date, String slot) {
        return query("bill_date=? AND slot=?", new String[]{date, slot});
    }

    public List<Bill> search(String kw) {
        return query("note LIKE ? OR category LIKE ?", new String[]{"%" + kw + "%", "%" + kw + "%"});
    }

    /** {支出合计, 收入合计} */
    public double[] totals(String from, String to) {
        double exp = 0, inc = 0;
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT type, SUM(amount) FROM bill WHERE bill_date>=? AND bill_date<=? GROUP BY type",
                new String[]{from, to});
        try {
            while (c.moveToNext()) {
                if (c.getInt(0) == Bill.EXPENSE) exp = c.getDouble(1);
                else inc = c.getDouble(1);
            }
        } finally {
            c.close();
        }
        return new double[]{exp, inc};
    }

    public int count(String from, String to) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM bill WHERE bill_date>=? AND bill_date<=?", new String[]{from, to});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 分类合计（仅支出），按金额降序 */
    public Map<String, Double> byCategory(String from, String to) {
        Map<String, Double> out = new LinkedHashMap<>();
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT category, SUM(amount) s FROM bill WHERE type=0 AND bill_date>=? AND bill_date<=? " +
                        "GROUP BY category ORDER BY s DESC", new String[]{from, to});
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getDouble(1));
        } finally {
            c.close();
        }
        return out;
    }

    /** 每天每类（仅支出）：day -> (cat -> amount)，供堆叠柱状图 */
    public LinkedHashMap<String, Map<String, Double>> stackedByDay(String from, String to) {
        LinkedHashMap<String, Map<String, Double>> out = new LinkedHashMap<>();
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT bill_date, category, SUM(amount) FROM bill WHERE type=0 AND bill_date>=? AND bill_date<=? " +
                        "GROUP BY bill_date, category", new String[]{from, to});
        try {
            while (c.moveToNext()) {
                String day = c.getString(0);
                Map<String, Double> m = out.get(day);
                if (m == null) {
                    m = new LinkedHashMap<>();
                    out.put(day, m);
                }
                m.put(c.getString(1), c.getDouble(2));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 某天某时段的合计（这一格页用） */
    public double sumOf(String date, String slot) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT SUM(amount) FROM bill WHERE bill_date=? AND slot=? AND type=0", new String[]{date, slot});
        try {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 历史数据修正：分类属于收入类但 type 记成支出的，改回来。返回修正条数。 */
    public int fixTypes() {
        int fixed = 0;
        android.database.Cursor c = helper.getWritableDatabase().rawQuery(
                "SELECT id, category, type FROM bill", null);
        java.util.List<long[]> fix = new java.util.ArrayList<>();
        java.util.List<String> cats = new java.util.ArrayList<>();
        try {
            while (c.moveToNext()) {
                String cat = c.getString(1);
                int type = c.getInt(2);
                int want = Category.byCode(cat).type;
                if (want != type) {
                    fix.add(new long[]{c.getLong(0), want});
                    cats.add(cat);
                }
            }
        } finally {
            c.close();
        }
        for (long[] row : fix) {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("type", (int) row[1]);
            helper.getWritableDatabase().update("bill", v, "id=?", new String[]{String.valueOf(row[0])});
            fixed++;
        }
        return fixed;
    }

    public void clearAll() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("bill", null, null);
            // Keep event tombstones: replayed notifications must not repopulate a cleared ledger.
            db.execSQL("UPDATE payment_event SET state='ignored', raw='', merchant='', snapshot='', transaction_id='', amount=0, bill_id=0");
            db.execSQL("UPDATE agent_action SET state='cancelled' WHERE state IN ('pending','applied')");
            db.execSQL("UPDATE agent_action SET payload='{}', before_json='[]', after_json='[]'");
            db.delete("agent_message", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public boolean isEmpty() {
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM bill", null);
        try {
            return !c.moveToFirst() || c.getInt(0) == 0;
        } finally {
            c.close();
        }
    }
}
