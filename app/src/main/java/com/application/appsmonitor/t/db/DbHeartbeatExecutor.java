package com.application.appsmonitor.t.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

import com.application.appsmonitor.t.data.AppItem;

/**
 * 心跳会话表访问器。
 * 全系统唯一的时长真相源：所有“使用时长/时间线”都从这张表读，
 * 不再依赖 UsageStatsManager 的 queryEvents / queryUsageStats。
 *
 * 写入方（UsageMonitorService）每拍调用 {@link #openSession} 或 {@link #updateEnd}，
 * 读入方（首页 / 详情页 / 监控档位）用 aggregate / sessions / sumByDay。
 */
public class DbHeartbeatExecutor {

    /** 一段前台+亮屏会话的起止（毫秒）。 */
    public static class Session {
        public final long start;
        public final long end;

        Session(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static DbHeartbeatExecutor sInstance;
    private static DbHelper mHelper;

    private DbHeartbeatExecutor() {
    }

    public static void init(Context context) {
        mHelper = new DbHelper(context);
        sInstance = new DbHeartbeatExecutor();
    }

    public static DbHeartbeatExecutor getInstance() {
        return sInstance;
    }

    /** 新开一段会话，返回行 id。 */
    public long openSession(String pkg, String day, long start, long end) {
        ContentValues values = new ContentValues();
        values.put(DbConst.TableHeartbeat.FIELD_PACKAGE_NAME, pkg);
        values.put(DbConst.TableHeartbeat.FIELD_DAY, day);
        values.put(DbConst.TableHeartbeat.FIELD_START, start);
        values.put(DbConst.TableHeartbeat.FIELD_END, end);
        return mHelper.getWritableDatabase().insert(
                DbConst.TableHeartbeat.TABLE_NAME, null, values);
    }

    /** 会话仍在继续时，把结束时间推进到本拍时刻（进程被杀则停在最后一次推进处）。 */
    public void updateEnd(long id, long end) {
        if (id <= 0) return;
        ContentValues values = new ContentValues();
        values.put(DbConst.TableHeartbeat.FIELD_END, end);
        mHelper.getWritableDatabase().update(
                DbConst.TableHeartbeat.TABLE_NAME, values,
                DbConst.TableHeartbeat._ID + " = ?",
                new String[]{String.valueOf(id)});
    }

    /** 指定包在指定日期（yyyyMMdd）的累计前台时长（毫秒）。 */
    public long sumByDay(String pkg, String day) {
        long result = 0;
        Cursor cursor = null;
        try {
            cursor = mHelper.getReadableDatabase().rawQuery(
                    "SELECT SUM(" + DbConst.TableHeartbeat.FIELD_END + " - "
                            + DbConst.TableHeartbeat.FIELD_START + ") FROM "
                            + DbConst.TableHeartbeat.TABLE_NAME
                            + " WHERE " + DbConst.TableHeartbeat.FIELD_PACKAGE_NAME + " = ?"
                            + " AND " + DbConst.TableHeartbeat.FIELD_DAY + " = ?",
                    new String[]{pkg, day});
            if (cursor.moveToFirst()) {
                result = cursor.getLong(0);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        if (result < 0) result = 0;
        return result;
    }

    /** 时间范围内、按包聚合的总时长/会话数/最近结束时间（供首页列表）。 */
    public List<AppItem> aggregate(long startTs, long endTs) {
        List<AppItem> items = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = mHelper.getReadableDatabase().rawQuery(
                    "SELECT " + DbConst.TableHeartbeat.FIELD_PACKAGE_NAME + ", "
                            + "SUM(" + DbConst.TableHeartbeat.FIELD_END + " - " + DbConst.TableHeartbeat.FIELD_START + "), "
                            + "COUNT(*), "
                            + "MAX(" + DbConst.TableHeartbeat.FIELD_END + ") "
                            + "FROM " + DbConst.TableHeartbeat.TABLE_NAME
                            + " WHERE " + DbConst.TableHeartbeat.FIELD_START + " >= ?"
                            + " AND " + DbConst.TableHeartbeat.FIELD_START + " <= ?"
                            + " GROUP BY " + DbConst.TableHeartbeat.FIELD_PACKAGE_NAME,
                    new String[]{String.valueOf(startTs), String.valueOf(endTs)});
            while (cursor.moveToNext()) {
                AppItem item = new AppItem();
                item.mPackageName = cursor.getString(0);
                item.mUsageTime = cursor.getLong(1);
                item.mCount = cursor.getInt(2);
                item.mEventTime = cursor.getLong(3);
                items.add(item);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return items;
    }

    /** 指定包在时间范围内的逐段会话（按开始时间升序，供详情页时间线）。 */
    public List<Session> sessions(String pkg, long startTs, long endTs) {
        List<Session> list = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = mHelper.getReadableDatabase().rawQuery(
                    "SELECT " + DbConst.TableHeartbeat.FIELD_START + ", "
                            + DbConst.TableHeartbeat.FIELD_END + " FROM "
                            + DbConst.TableHeartbeat.TABLE_NAME
                            + " WHERE " + DbConst.TableHeartbeat.FIELD_PACKAGE_NAME + " = ?"
                            + " AND " + DbConst.TableHeartbeat.FIELD_START + " >= ?"
                            + " AND " + DbConst.TableHeartbeat.FIELD_START + " <= ?"
                            + " ORDER BY " + DbConst.TableHeartbeat.FIELD_START + " ASC",
                    new String[]{pkg, String.valueOf(startTs), String.valueOf(endTs)});
            while (cursor.moveToNext()) {
                list.add(new Session(cursor.getLong(0), cursor.getLong(1)));
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public void clear() {
        mHelper.getWritableDatabase().delete(DbConst.TableHeartbeat.TABLE_NAME, null, null);
    }
}
