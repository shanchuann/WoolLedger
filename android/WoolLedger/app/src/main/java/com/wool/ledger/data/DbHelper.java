package com.wool.ledger.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DbHelper extends SQLiteOpenHelper {

    private static final String NAME = "wool.db";
    private static final int VERSION = 1;

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS bill");
        onCreate(db);
    }
}
