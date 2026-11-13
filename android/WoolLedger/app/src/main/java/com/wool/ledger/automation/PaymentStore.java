package com.wool.ledger.automation;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.wool.ledger.data.*;
import com.wool.ledger.util.DateUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/** Event and ledger writes share a transaction, including the idempotency marker. */
public final class PaymentStore {
    private final Context ctx;

    public PaymentStore(Context c) {
        ctx = c.getApplicationContext();
    }

    public static String hash(String s) {
        try {
            byte[] b =
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder o = new StringBuilder();
            for (byte x : b) o.append(String.format(Locale.ROOT, "%02x", x & 255));
            return o.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public long receive(String pkg, String notificationKey, String raw, long posted) {
        if (!Prefs.autoCapture(ctx) || !Prefs.captures(ctx, pkg)) return 0;
        PaymentParser.Result p = PaymentParser.parse(raw);
        if (!p.relevant) return 0;
        String key =
                hash(
                        pkg
                                + "|"
                                + (!p.transactionId.isEmpty()
                                        ? "tx:" + p.transactionId
                                        : "event:" + notificationKey + "|" + posted + "|" + raw));
        SQLiteDatabase db = DbHelper.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            // Strong transaction IDs and exact repeats are idempotent. Reused notification IDs can
            // represent new payments.
            long existing = 0;
            try (Cursor c =
                    db.rawQuery(
                            "SELECT id,state FROM payment_event WHERE event_key=?",
                            new String[] {key})) {
                if (c.moveToFirst()) {
                    if (!"pending".equals(c.getString(1))) return c.getLong(0);
                    existing = c.getLong(0);
                }
            }
            if (existing == 0)
                try (Cursor c =
                        db.rawQuery(
                                "SELECT id,state FROM payment_event WHERE package_name=? AND"
                                    + " notification_key=? AND posted=?",
                                new String[] {pkg, notificationKey, String.valueOf(posted)})) {
                    if (c.moveToFirst()) {
                        existing = c.getLong(0);
                        if (!"pending".equals(c.getString(1))) return existing;
                    }
                }
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(posted));
            boolean duplicate = false;
            String contentHash = hash(pkg + "|" + raw);
            try (Cursor c =
                    db.rawQuery(
                            "SELECT id FROM payment_event WHERE content_hash=? AND id!=?",
                            new String[] {contentHash, String.valueOf(existing)})) {
                duplicate = c.moveToFirst();
            }
            if (p.amount > 0)
                try (Cursor c =
                        db.rawQuery(
                                "SELECT id FROM bill WHERE type=? AND ABS(amount-?)<0.005 AND"
                                    + " bill_date=?",
                                new String[] {
                                    String.valueOf(p.type), String.valueOf(p.amount), day
                                })) {
                    duplicate = duplicate || c.moveToFirst();
                }
            ContentValues v = new ContentValues();
            v.put("content_hash", contentHash);
            v.put("event_key", key);
            v.put("notification_key", notificationKey);
            v.put("package_name", pkg);
            v.put("raw", raw.substring(0, Math.min(raw.length(), 1000)));
            v.put("posted", posted);
            v.put("amount", p.amount);
            v.put("type", p.type);
            v.put("category", p.category);
            v.put("merchant", p.merchant);
            v.put("transaction_id", p.transactionId);
            v.put("reason", duplicate ? "存在相同通知内容或同日同金额账目，可能重复，请核对" : p.reason);
            long id = existing;
            if (id == 0) id = db.insertOrThrow("payment_event", null, v);
            else db.update("payment_event", v, "id=?", new String[] {String.valueOf(id)});
            if (p.complete && !duplicate && Prefs.autoPost(ctx)) {
                Bill b = bill(p.amount, p.type, p.category, p.merchant, posted);
                new BillDao(ctx).insert(b);
                ContentValues done = new ContentValues();
                done.put("state", "posted");
                done.put("bill_id", b.id);
                done.put("snapshot", snapshot(b));
                db.update("payment_event", done, "id=?", new String[] {String.valueOf(id)});
            }
            db.setTransactionSuccessful();
            Prefs.markSeeded(ctx);
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public static Bill bill(double amount, int type, String category, String note, long posted) {
        Bill b = new Bill();
        b.amount = amount;
        b.type = type;
        b.category = category;
        b.note = note;
        b.billDate = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(posted));
        b.billTime = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(posted));
        b.slot = DateUtil.slotOf(b.billTime);
        b.createTime = DateUtil.nowFull();
        b.source = 3;
        return b;
    }

    public List<ContentValues> list(String state) {
        List<ContentValues> out = new ArrayList<>();
        String where = state == null ? "" : " WHERE state=?";
        try (Cursor c =
                DbHelper.get(ctx)
                        .getReadableDatabase()
                        .rawQuery(
                                "SELECT * FROM payment_event"
                                        + where
                                        + " ORDER BY posted DESC LIMIT 100",
                                state == null ? null : new String[] {state})) {
            while (c.moveToNext()) {
                ContentValues v = new ContentValues();
                android.database.DatabaseUtils.cursorRowToContentValues(c, v);
                out.add(v);
            }
        }
        return out;
    }

    public int pendingCount() {
        try (Cursor c =
                DbHelper.get(ctx)
                        .getReadableDatabase()
                        .rawQuery(
                                "SELECT COUNT(*) FROM payment_event WHERE state='pending'", null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    public void confirm(long id, Bill b) {
        com.wool.ledger.agent.LedgerCodec.validate(b);
        SQLiteDatabase db = DbHelper.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try (Cursor c =
                db.rawQuery(
                        "SELECT state FROM payment_event WHERE id=?",
                        new String[] {String.valueOf(id)})) {
            if (!c.moveToFirst() || !"pending".equals(c.getString(0)))
                throw new IllegalStateException("此通知已处理，请刷新");
            b.source = 3;
            new BillDao(ctx).insert(b);
            ContentValues v = new ContentValues();
            v.put("state", "posted");
            v.put("bill_id", b.id);
            v.put("snapshot", snapshot(b));
            db.update("payment_event", v, "id=?", new String[] {String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void ignore(long id) {
        ContentValues v = new ContentValues();
        v.put("state", "ignored");
        DbHelper.get(ctx)
                .getWritableDatabase()
                .update(
                        "payment_event",
                        v,
                        "id=? AND state='pending'",
                        new String[] {String.valueOf(id)});
    }

    private static String snapshot(Bill b) {
        try {
            return com.wool.ledger.agent.LedgerCodec.json(b).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public void undo(long id) {
        SQLiteDatabase db = DbHelper.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try (Cursor c =
                db.rawQuery(
                        "SELECT bill_id,snapshot FROM payment_event WHERE id=? AND state='posted'",
                        new String[] {String.valueOf(id)})) {
            if (!c.moveToFirst()) throw new IllegalStateException("此记录已处理");
            Bill b = new BillDao(ctx).get(c.getLong(0));
            // Edited records must not be silently deleted by an old notification action.
            if (b != null && !snapshot(b).equals(c.getString(1)))
                throw new IllegalStateException("账目已被修改，请到账单详情处理");
            if (b != null) new BillDao(ctx).delete(b.id);
            ContentValues v = new ContentValues();
            v.put("state", "ignored");
            db.update("payment_event", v, "id=?", new String[] {String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
