package com.application.appsmonitor.t;

/**
 * AppConst
 * Created by zb on 18/12/2017.
 */

public final class AppConst {
    public static final boolean CRASH_TO_FILE = false;
    public static final long USAGE_TIME_MIX = 5000;
    public static final String GP_DETAIL_PREFIX = "https://play.google.com/store/apps/details?id=";
    public static final String AF_KEY = "cb34ade7-ee68-48f4-91c0-0eb5fc6c2704";
    public static final String BUG_KEY = "4a59b2abb6";
    public static final String LOG_DIR = "com.application.appsmonitor.t";
    public static final long REMOTE_CONFIG_CACHE_TIME = 6 * 60 * 60;

    // ===== 实时时长监控（固定档位提醒）配置 =====
    // 只监控这里列出的包名。当前为占位，请把你要盯的应用包名填进来
    // （可用 adb shell pm list packages 或“应用详情”页顶部 pkg_name 查看）。
    public static final String[] MONITOR_PACKAGES = {
            "com.android.chrome",
    };
    // 每日使用上限（分钟），用于计算“还剩多少分钟”
    public static final int MONITOR_DAILY_LIMIT_MIN = 120;
    // 固定提醒档位（已用分钟数达到即提醒一次，同日同档不重复）
    public static final int[] MONITOR_TIERS_MIN = {1, 20, 55, 115};
    // 轮询间隔（毫秒）
    public static final long MONITOR_CHECK_INTERVAL_MS = 60 * 1000L;
}
