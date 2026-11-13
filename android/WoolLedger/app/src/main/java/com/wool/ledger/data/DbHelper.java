package com.wool.ledger.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DbHelper extends SQLiteOpenHelper {

    private static final String NAME = "wool.db";
    private static final int VERSION = 2;

    private static DbHelper sInstance;

    public static synchronized DbHelper get(Context ctx) {
        if (sInstance == null) sInstance = new DbHelper(ctx.getApplicationContext());
        return sInstance;
    }

    private DbHelper(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bill (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type INTEGER NOT NULL DEFAULT 0," +
                "amount REAL NOT NULL," +
                "category TEXT NOT NULL DEFAULT 'other'," +
                "note TEXT," +
                "bill_date TEXT NOT NULL," +
                "bill_time TEXT NOT NULL DEFAULT '12:00'," +
                "slot TEXT NOT NULL DEFAULT '午'," +
                "create_time TEXT NOT NULL," +
                "source INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_bill_date ON bill(bill_date)");
        createAutomation(db);
    }

    private static void createAutomation(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS payment_event (id INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT NOT NULL UNIQUE, notification_key TEXT NOT NULL, package_name TEXT NOT NULL, raw TEXT NOT NULL, posted INTEGER NOT NULL, amount REAL NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 0, category TEXT NOT NULL DEFAULT 'other', merchant TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'pending', bill_id INTEGER NOT NULL DEFAULT 0, transaction_id TEXT NOT NULL DEFAULT '', snapshot TEXT NOT NULL DEFAULT '', content_hash TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payment_state ON payment_event(state,posted)");
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_action (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL, before_json TEXT NOT NULL DEFAULT '', after_json TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'pending', created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ledger_setting (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_message (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, body TEXT NOT NULL, created INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createAutomation(db);
    }
}
