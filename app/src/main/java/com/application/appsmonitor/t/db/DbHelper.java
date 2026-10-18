package com.application.appsmonitor.t.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * DB Helper
 * Created by zb on 18/12/2017.
 */

public class DbHelper extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 3;
    private static final String DATABASE_NAME = DbConst.DATABASE_NAME;

    private static final String SQL_CREATE_IGNORE =
            "CREATE TABLE " + DbConst.TableIgnore.TABLE_NAME + " (" +
                    DbConst.TableIgnore._ID + " INTEGER PRIMARY KEY," +
                    DbConst.TableIgnore.FIELD_PACKAGE_NAME + " TEXT," +
                    DbConst.TableIgnore.FIELD_CREATE_TIME + " INTEGER)";

    private static final String SQL_CREATE_HISTORY =
            "CREATE TABLE " + DbConst.TableHistory.TABLE_NAME + " (" +
                    DbConst.TableHistory._ID + " INTEGER PRIMARY KEY," +
                    DbConst.TableHistory.FIELD_PACKAGE_NAME + " TEXT," +
                    DbConst.TableHistory.FIELD_NAME + " TEXT," +
                    DbConst.TableHistory.FIELD_DATE + " TEXT," +
                    DbConst.TableHistory.FIELD_SYSTEM + " INTEGER," +
                    DbConst.TableHistory.FIELD_MOBILE_TRAFFIC + " INTEGER," +
                    DbConst.TableHistory.FIELD_TIMESTAMP + " INTEGER," +
                    DbConst.TableHistory.FIELD_DURATION + " INTEGER)";

    private static final String SQL_CREATE_HEARTBEAT =
            "CREATE TABLE " + DbConst.TableHeartbeat.TABLE_NAME + " (" +
                    DbConst.TableHeartbeat._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    DbConst.TableHeartbeat.FIELD_PACKAGE_NAME + " TEXT," +
                    DbConst.TableHeartbeat.FIELD_DAY + " TEXT," +
                    DbConst.TableHeartbeat.FIELD_START + " INTEGER," +
                    DbConst.TableHeartbeat.FIELD_END + " INTEGER)";

    private static final String SQL_DELETE_IGNORE =
            "DROP TABLE IF EXISTS " + DbConst.TableIgnore.TABLE_NAME;

    private static final String SQL_DELETE_HISTORY =
            "DROP TABLE IF EXISTS " + DbConst.TableHistory.TABLE_NAME;

    private static final String SQL_DELETE_HEARTBEAT =
            "DROP TABLE IF EXISTS " + DbConst.TableHeartbeat.TABLE_NAME;

    DbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_IGNORE);
        db.execSQL(SQL_CREATE_HISTORY);
        db.execSQL(SQL_CREATE_HEARTBEAT);
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(SQL_DELETE_IGNORE);
        db.execSQL(SQL_DELETE_HISTORY);
        db.execSQL(SQL_DELETE_HEARTBEAT);
        onCreate(db);
    }

    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }
}
