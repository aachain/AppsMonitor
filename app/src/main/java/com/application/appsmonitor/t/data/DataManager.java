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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.application.appsmonitor.t.AppConst;
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
    // 详情页合成会话复用前台(1)/后台(2)事件类型常量，保持与 DetailActivity 渲染一致。
    // 时长统计已改为直接采用系统 bucket（queryUsageStats），不再自行做事件配对。
    private static final int EVENT_START = UsageEvents.Event.MOVE_TO_FOREGROUND;
    private static final int EVENT_END_PAUSED = UsageEvents.Event.MOVE_TO_BACKGROUND;

    private static DataManager mInstance;

    public static void init() {
        mInstance = new DataManager();
    }

    public static DataManager getInstance() {
        return mInstance;
    }

    /**
     * 查询指定包“今天 00:00 到现在”的前台累计时长（毫秒）。
     * 用于实时监控服务轮询判断是否达到提醒档位。
     * 未授予使用情况访问权限时返回 0（不会产生误报）。
     *
     * 口径：直接采用系统 UsageStatsService 自己聚合好的 bucket（queryUsageStats 的
     * totalTimeInForeground），不再自行用 queryEvents 配对统计。
     * 已知局限：totalTimeInForeground 只累计“已结束的前后台会话”，当前仍在前台未退出的
     * 那一段不计入，故实时监控会滞后一个会话。
     */
    public long getTodayUsageMillis(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) return 0;
        if (!hasPermission(context)) return 0;
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return 0;
        long[] range = AppUtil.getTimeRange(SortEnum.TODAY);
        long total = 0;
        try {
            List<UsageStats> stats = manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, range[0], range[1]);
            if (stats != null) {
                for (UsageStats s : stats) {
                    if (s != null && pkg.equals(s.getPackageName())) {
                        total += s.getTotalTimeInForeground();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getTodayUsageMillis queryUsageStats error: " + e.getMessage(), e);
        }
        if (total < 0) total = 0;
        Log.d(TAG, "getTodayUsageMillis " + pkg + " bucket total=" + (total / 1000) + "s");
        return total;
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
            // 这种情况仍视为已授权，后续 getApps 会走 queryUsageStats 兜底。
            // 使用 INTERVAL_DAILY，与 appendFromUsageStats 兜底路径保持一致。
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
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return items;

        long[] range = AppUtil.getTimeRange(SortEnum.getSortEnum(offset));
        AppItem template = new AppItem();
        template.mPackageName = target;
        template.mName = AppUtil.parsePackageName(context.getPackageManager(), target);

        // 直接采用系统 bucket（queryUsageStats 的 totalTimeInForeground）合成一条会话，
        // 不再自行用 queryEvents 配对。系统 bucket 无逐次开关时间线，故详情页展示为一段。
        appendTargetFromUsageStats(manager, target, range, template, items);
        return items;
    }

    public List<AppItem> getApps(Context context, int sort, int offset) {

        List<AppItem> items = new ArrayList<>();
        List<AppItem> newList = new ArrayList<>();
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        long[] range = AppUtil.getTimeRange(SortEnum.getSortEnum(offset));
        if (manager != null) {
            Log.d(TAG, "Querying events from " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(range[0]))
                    + " to " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(range[1])));
            // 直接采用系统 UsageStatsService 聚合好的 bucket（queryUsageStats 的
            // totalTimeInForeground）按包汇总，不再自行用 queryEvents 配对统计。
            Map<String, AppItem> itemsByPkg = new HashMap<>();
            appendFromUsageStats(manager, range, items, itemsByPkg);
        } else {
            Log.e(TAG, "UsageStatsManager is null");
        }
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

    /**
     * 系统 bucket 统计（主路径）：用 queryUsageStats 的
     * totalTimeInForeground 汇总每包使用情况。手动按包聚合，避免同一包多 bucket 重复。
     * 注意：UsageStats 拿不到精确事件次数，这里给 duration>阈值 的应用粗略 +1 让 count 不为 0。
     */
    private void appendFromUsageStats(UsageStatsManager usm, long[] range,
                                      List<AppItem> items, Map<String, AppItem> itemsByPkg) {
        Map<String, long[]> agg = new HashMap<>();
        Map<String, Long> lastMap = new HashMap<>();
        try {
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, range[0], range[1]);
            if (stats == null || stats.isEmpty()) {
                Log.w(TAG, "queryUsageStats fallback also returned empty. Check HyperOS "
                        + "usage access permission & battery optimization exemption.");
                return;
            }
            for (UsageStats us : stats) {
                if (us == null || us.getPackageName() == null) continue;
                long dur = us.getTotalTimeInForeground();
                if (dur <= 0) continue;
                String pkg = us.getPackageName();
                long[] bucket = agg.get(pkg);
                if (bucket == null) {
                    bucket = new long[]{0L};
                    agg.put(pkg, bucket);
                }
                bucket[0] += dur;
                Long prev = lastMap.get(pkg);
                long last = us.getLastTimeUsed();
                if (prev == null || last > prev) lastMap.put(pkg, last);
            }
            for (Map.Entry<String, long[]> e : agg.entrySet()) {
                String pkg = e.getKey();
                long duration = e.getValue()[0];
                Long last = lastMap.get(pkg);
                AppItem item = itemsByPkg.get(pkg);
                if (item == null) {
                    item = new AppItem();
                    item.mPackageName = pkg;
                    itemsByPkg.put(pkg, item);
                    items.add(item);
                }
                item.mUsageTime += duration;
                if (last != null && last > item.mEventTime) item.mEventTime = last;
                if (duration > AppConst.USAGE_TIME_MIX) item.mCount += 1;
            }
        } catch (Exception ex) {
            Log.e(TAG, "queryUsageStats fallback failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 详情页时间线（主路径，基于系统 bucket）：合成一条 "fg + bg" 会话，
     * 时间基于 UsageStats.lastTimeUsed 与 totalTimeInForeground 反推。
     */
    private void appendTargetFromUsageStats(UsageStatsManager usm, String target,
                                            long[] range, AppItem template,
                                            List<AppItem> items) {
        try {
            long dur = 0L;
            long last = 0L;
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, range[0], range[1]);
            if (stats != null) {
                for (UsageStats us : stats) {
                    if (us == null || !target.equals(us.getPackageName())) continue;
                    dur += us.getTotalTimeInForeground();
                    if (us.getLastTimeUsed() > last) last = us.getLastTimeUsed();
                }
            }
            if (dur <= 0 || last <= 0) {
                Log.w(TAG, "queryUsageStats fallback empty for target " + target);
                return;
            }
            long begin = Math.max(range[0], last - dur);
            AppItem fg = template.copy();
            fg.mEventTime = begin;
            fg.mEventType = EVENT_START;
            fg.mUsageTime = 0;
            items.add(fg);
            AppItem bg = template.copy();
            bg.mEventTime = last;
            bg.mEventType = EVENT_END_PAUSED;
            bg.mUsageTime = Math.max(0L, last - begin);
            if (bg.mUsageTime > AppConst.USAGE_TIME_MIX) bg.mCount = 1;
            items.add(bg);
        } catch (Exception ex) {
            Log.e(TAG, "Timeline fallback failed for " + target + ": " + ex.getMessage(), ex);
        }
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
