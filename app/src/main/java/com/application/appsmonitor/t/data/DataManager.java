package com.application.appsmonitor.t.data;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.application.appsmonitor.t.AppConst;
import com.application.appsmonitor.t.db.DbHeartbeatExecutor;
import com.application.appsmonitor.t.db.DbIgnoreExecutor;
import com.application.appsmonitor.t.util.AppUtil;
import com.application.appsmonitor.t.util.PreferenceManager;
import com.application.appsmonitor.t.util.SortEnum;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

/**
 * DataManager
 * Created by zb on 18/12/2017.
 */

public class DataManager {

    private static final String TAG = "DataManager";
    // UsageEvents.Event 在 API 29 后语义重命名但常量值不变：
    //   ACTIVITY_RESUMED(1) == MOVE_TO_FOREGROUND(1)
    //   ACTIVITY_PAUSED(2)  == MOVE_TO_BACKGROUND(2)
    //   ACTIVITY_STOPPED(23) —— API 29 起新增；HyperOS/MIUI 与平板多窗口场景常只发 23 不发 2
    private static final int EVENT_START = UsageEvents.Event.MOVE_TO_FOREGROUND;
    private static final int EVENT_END_PAUSED = UsageEvents.Event.MOVE_TO_BACKGROUND;
    private static final int EVENT_END_STOPPED = 23;
    // 单个会话合理上限（4 小时）：HyperOS 漏发 END 时 open session 会被补结算到
    // range[1]；若无上限会把“忘记关的前台应用”膨胀成整天数据。
    private static final long MAX_SESSION_MS = 4L * 3600L * 1000L;
    // 一个应用不可能在没有重新进入前台（START）的情况下“第二次退到后台”，
    // 因此用“本事件流内是否已见过该包 START”作为确定性判据，识别并丢弃重复 END，
    // 取代不可靠的时间窗口去重（PAUSED/STOPPED 双发、以及包切换 cascade 结算后
    // 延迟到达的后台事件都可能是重复 END）

    private static DataManager mInstance;

    public static void init() {
        mInstance = new DataManager();
    }

    public static DataManager getInstance() {
        return mInstance;
    }

    /**
     * 屏幕当前是否点亮且可交互（熄屏/锁屏返回 false）。
     * 用于心跳计时：只有亮屏时才可能计入前台时长。
     */
    public boolean isScreenInteractive(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    /**
     * 判定当前处于前台的应用包名。
     * 思路：回看最近 windowMs 内的“进入前台（EVENT_START / ACTIVITY_RESUMED）”事件，
     * 取时间戳最晚的一条作为当前前台包；若窗口内没有任何新事件，说明这段时间
     * 未发生前后台切换，则沿用上一次观测到的前台包（持久化在 SharedPreferences）。
     * 返回 null 表示无法判定（无权限 / 从未观测到）。
     */
    public String getForegroundPackage(Context context, long windowMs) {
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return null;
        long now = System.currentTimeMillis();
        String result = null;
        try {
            UsageEvents events = manager.queryEvents(now - windowMs, now);
            if (events != null) {
                long bestTs = 0;
                UsageEvents.Event e = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    if (e.getEventType() == EVENT_START && e.getTimeStamp() >= bestTs) {
                        bestTs = e.getTimeStamp();
                        result = e.getPackageName();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "getForegroundPackage error: " + ex.getMessage(), ex);
        }
        String key = "last_fg_pkg";
        if (!TextUtils.isEmpty(result)) {
            PreferenceManager.getInstance().putString(key, result);
            return result;
        }
        // 窗口内无新事件：沿用最后一次已知前台包，避免长时间停留同一界面时误判为“不在前台”
        String saved = PreferenceManager.getInstance().getString(key);
        return TextUtils.isEmpty(saved) ? null : saved;
    }

    public void requestPermission(Context context) {
        Intent intent = new Intent(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public boolean hasPermission(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (manager == null) return false;
            // HyperOS/MIUI 上刚刚授权完，AppOps 已放开但底层事件服务需要几秒预热；
            // 探测窗口 30 分钟足以覆盖近期活动，且避免 AppService 400ms 轮询时
            // 每次都拉 6 小时事件流造成的主线程卡顿。
            long endTime = System.currentTimeMillis();
            long startTime = endTime - 30L * 60L * 1000L;
            // 路径 1：queryEvents 能拿到实际事件（旧机正常情况）
            UsageEvents events = manager.queryEvents(startTime, endTime);
            if (events != null && events.hasNextEvent()) {
                return true;
            }
            // 路径 2：queryEvents 空但 queryUsageStats 有数据 —— HyperOS 已知行为，
            // 仍视为已授权（心跳前台判定依赖 queryEvents，但授权探测不应因此误判为未授权）。
            List<UsageStats> stats = manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime);
            if (stats != null && !stats.isEmpty()) {
                Log.w(TAG, "queryEvents empty but queryUsageStats has data (HyperOS/MIUI mode)");
                return true;
            }
            Log.w(TAG, "Usage permission reported granted but no data source returned anything");
        } catch (Exception e) {
            Log.e(TAG, "Permission check error: " + e.getMessage(), e);
        }
        return false;
    }

    public List<AppItem> getTargetAppTimeline(Context context, String target, int offset) {
        List<AppItem> items = new ArrayList<>();
        if (TextUtils.isEmpty(target)) return items;
        DbHeartbeatExecutor db = DbHeartbeatExecutor.getInstance();
        if (db == null) return items;

        long[] range = AppUtil.getTimeRange(SortEnum.getSortEnum(offset));
        AppItem template = new AppItem();
        template.mPackageName = target;
        template.mName = AppUtil.parsePackageName(context.getPackageManager(), target);

        // 全系统唯一口径：时间线 = 该包在范围内的每一段“前台+亮屏”心跳会话
        List<DbHeartbeatExecutor.Session> sessions = db.sessions(target, range[0], range[1]);
        for (DbHeartbeatExecutor.Session s : sessions) {
            AppItem fg = template.copy();
            fg.mEventTime = s.start;
            fg.mEventType = EVENT_START;
            fg.mUsageTime = 0;
            items.add(fg);
            AppItem bg = template.copy();
            bg.mEventTime = s.end;
            bg.mEventType = EVENT_END_PAUSED;
            long dur = s.end - s.start;
            if (dur < 0) dur = 0;
            bg.mUsageTime = dur;
            if (bg.mUsageTime > AppConst.USAGE_TIME_MIX) bg.mCount = 1;
            items.add(bg);
        }
        return items;
    }

    public List<AppItem> getApps(Context context, int sort, int offset) {

        List<AppItem> items = new ArrayList<>();
        List<AppItem> newList = new ArrayList<>();
        DbHeartbeatExecutor db = DbHeartbeatExecutor.getInstance();
        long[] range = AppUtil.getTimeRange(SortEnum.getSortEnum(offset));
        if (db == null) {
            Log.e(TAG, "DbHeartbeatExecutor not initialized");
            return newList;
        }
        // 全系统唯一口径：列表时长/次数/最近使用时间均来自心跳会话表
        items = db.aggregate(range[0], range[1]);
        // 按照使用时长排序
        Map<String, Long> mobileData = new HashMap<>();
        boolean valid = false;
        
        if (items.size() > 0) {
            Log.d("DataManager", "Found " + items.size() + " apps with usage data");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                valid = true;
                NetworkStatsManager networkStatsManager = (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
                TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                mobileData = getMobileData(context, telephonyManager, networkStatsManager, offset);
            }

            boolean hideSystem = PreferenceManager.getInstance().getBoolean(PreferenceManager.PREF_SETTINGS_HIDE_SYSTEM_APPS);
            boolean hideUninstall = PreferenceManager.getInstance().getBoolean(PreferenceManager.PREF_SETTINGS_HIDE_UNINSTALL_APPS);
            List<IgnoreItem> ignoreItems = DbIgnoreExecutor.getInstance().getAllItems();
            PackageManager packageManager = context.getPackageManager();
            for (AppItem item : items) {
                if (!AppUtil.openable(packageManager, item.mPackageName)) {
                    continue;
                }
                if (hideSystem && AppUtil.isSystemApp(packageManager, item.mPackageName)) {
                    continue;
                }
                if (hideUninstall && !AppUtil.isInstalled(packageManager, item.mPackageName)) {
                    continue;
                }
                if (inIgnoreList(ignoreItems, item.mPackageName)) {
                    continue;
                }
                if (valid) {
                    String key = "u" + AppUtil.getAppUid(packageManager, item.mPackageName);
                    if (mobileData.size() > 0 && mobileData.containsKey(key)) {
                        item.mMobile = mobileData.get(key);
                    }
                }
                item.mName = AppUtil.parsePackageName(packageManager, item.mPackageName);
                newList.add(item);
            }

            if (sort == 0) {
                Collections.sort(newList, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem left, AppItem right) {
                        return (int) (right.mUsageTime - left.mUsageTime);
                    }
                });
            } else if (sort == 1) {
                Collections.sort(newList, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem left, AppItem right) {
                        return (int) (right.mEventTime - left.mEventTime);
                    }
                });
            } else if (sort == 2) {
                Collections.sort(newList, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem left, AppItem right) {
                        return right.mCount - left.mCount;
                    }
                });
            } else {
                Collections.sort(newList, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem left, AppItem right) {
                        return (int) (right.mMobile - left.mMobile);
                    }
                });
            }
        }
        return newList;
    }

    private Map<String, Long> getMobileData(Context context, TelephonyManager tm, NetworkStatsManager nsm, int offset) {
        Map<String, Long> result = new HashMap<>();
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            long[] range = AppUtil.getTimeRange(SortEnum.getSortEnum(offset));
            NetworkStats networkStatsM;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    networkStatsM = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, range[0], range[1]);
                    if (networkStatsM != null) {
                        while (networkStatsM.hasNextBucket()) {
                            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                            networkStatsM.getNextBucket(bucket);
                            String key = "u" + bucket.getUid();
                            Log.d("******", key + " " + bucket.getTxBytes() + "");
                            if (result.containsKey(key)) {
                                result.put(key, result.get(key) + bucket.getTxBytes() + bucket.getRxBytes());
                            } else {
                                result.put(key, bucket.getTxBytes() + bucket.getRxBytes());
                            }
                        }
                    }
                }
            } catch (RemoteException e) {
                e.printStackTrace();
                Log.e(">>>>>", e.getMessage());
            }
        }
        return result;
    }

    private AppItem containItem(List<AppItem> items, String packageName) {
        for (AppItem item : items) {
            if (item.mPackageName.equals(packageName)) return item;
        }
        return null;
    }

    private boolean inIgnoreList(List<IgnoreItem> items, String packageName) {
        for (IgnoreItem item : items) {
            if (item.mPackageName.equals(packageName)) return true;
        }
        return false;
    }

    class ClonedEvent {

        String packageName;
        String eventClass;
        long timeStamp;
        int eventType;

        ClonedEvent(UsageEvents.Event event) {
            packageName = event.getPackageName();
            eventClass = event.getClassName();
            timeStamp = event.getTimeStamp();
            eventType = event.getEventType();
        }
    }
}
