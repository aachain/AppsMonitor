package com.application.appsmonitor.t.db;

import android.provider.BaseColumns;

/**
 * App Table Schema
 * Created by zb on 18/12/2017.
 */

final class DbConst {

    static final String DATABASE_NAME = "appsmonitor";

    private DbConst() {
    }

    static class TableIgnore implements BaseColumns {
        static final String TABLE_NAME = "ignore";
        static final String FIELD_PACKAGE_NAME = "package_name";
        static final String FIELD_CREATE_TIME = "created_time";
    }

    static class TableHistory implements BaseColumns {
        static final String TABLE_NAME = "history";
        static final String FIELD_DATE = "date";
        static final String FIELD_PACKAGE_NAME = "package_name";
        static final String FIELD_NAME = "name";
        static final String FIELD_SYSTEM = "is_system";
        static final String FIELD_DURATION = "duration";
        static final String FIELD_TIMESTAMP = "timestamp";
        static final String FIELD_MOBILE_TRAFFIC = "mobile";
    }

    /**
     * 心跳会话表：全系统唯一的时长真相源。
     * 一行 = 一段“某应用在前台且屏幕亮”的连续会话。
     */
    static class TableHeartbeat implements BaseColumns {
        static final String TABLE_NAME = "heartbeat_session";
        static final String FIELD_PACKAGE_NAME = "package_name";
        static final String FIELD_DAY = "day";
        static final String FIELD_START = "start_ts";
        static final String FIELD_END = "end_ts";
    }

}
