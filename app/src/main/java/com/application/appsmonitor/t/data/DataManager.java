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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    // UsageEvents.Event 在 API 29 后语义重命名但常量值不变：
    //   ACTIVITY_RESUMED(1) == MOVE_TO_FOREGROUND(1)
    //   ACTIVITY_PAUSED(2)  == MOVE_TO_BACKGROUND(2)
    //   ACTIVITY_STOPPED(23) —— API 29 起新增；HyperOS/MIUI 与平板多窗口场景常只发 23 不发 2
    private static final int EVENT_START = UsageEvents.Event.MOVE_TO_FOREGROUND;
    private static final int EVENT_END_PAUSED = UsageEvents.Event.MOVE_TO_BACKGROUND;
    private static final int EVENT_END_STOPPED = 23;
    // 屏幕事件（全局、无包名）：熄屏作为“用户真正离开”的会话结束信号。
    // SCREEN_INTERACTIVE(26)/SCREEN_NON_INTERACTIVE(27) 自 API 21 起提供，minSdk22 可用。
    private static final int EVENT_SCREEN_ON = UsageEvents.Event.SCREEN_INTERACTIVE;
    private static final int EVENT_SCREEN_OFF = UsageEvents.Event.SCREEN_NON_INTERACTIVE;
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
     * 查询指定包“今天 00:00 到现在”的前台累计时长（毫秒）。
     * 用于实时监控服务轮询判断是否达到提醒档位。
     * 未授予使用情况访问权限时返回 0（不会产生误报）。
     *
     * 关键：必须用 queryEvents 配对，并把“当前仍在前台、尚未结算的 open session”
     * 补算到 now——因为 queryUsageStats().getTotalTimeInForeground() 只统计“已经
     * 结束的前后台会话”，正在运行的 App 那一段根本不计入。若沿用 queryUsageStats，
     * 就会出现“正开着被监控 App 时读数一直是 0、永远够不到提醒档位”的问题。
     * 仅当整个事件流对该包一条事件都没有时（个别 HyperOS 机型行为），才退回 queryUsageStats 兜底。
     */
    public long getTodayUsageMillis(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) return 0;
        if (!hasPermission(context)) return 0;
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return 0;
        long[] range = AppUtil.getTimeRange(SortEnum.TODAY);
        long total = 0;
        long openStart = 0;   // >0 表示目标包会话进行中（已 START 未熄屏）
        boolean seen = false; // 本流是否已为目标包起过会话（含跨零点半开兜底）
        int eventCount = 0;
        try {
            UsageEvents events = manager.queryEvents(range[0], range[1]);
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    int type = event.getEventType();
                    long t = event.getTimeStamp();
                    // 熄屏（全局事件，无包名）：结束当前会话——这是“用户真正离开”的信号
                    if (type == EVENT_SCREEN_OFF) {
                        if (openStart > 0) {
                            long dur = t - openStart;
                            if (dur < 0) dur = 0;
                            if (dur > MAX_SESSION_MS) dur = MAX_SESSION_MS;
                            total += dur;
                            openStart = 0;
                            seen = false; // 熄屏后允许重新起会话
                        }
                        continue;
                    }
                    if (type == EVENT_SCREEN_ON) continue;
                    if (!pkg.equals(event.getPackageName())) continue;
                    eventCount++;
                    if (type == EVENT_START) {
                        // 只要没熄屏就只有一个进行中会话：已 open 时忽略重复 START（活动切换/闪屏）
                        if (openStart == 0) {
                            seen = true;
                            openStart = t;
                        }
                    } else if (type == EVENT_END_PAUSED || type == EVENT_END_STOPPED) {
                        // 退后台不结束会话（口径：启动了没退出就一直计时）；
                        // 仅跨零点半开（本流未起过会话）时用 range[0] 兜起点
                        if (openStart == 0 && !seen) {
                            seen = true;
                            openStart = range[0];
                        }
                    }
                }
            }
            // 边界收尾：查询结束仍未熄屏，结算到 now
            if (openStart > 0 && range[1] > openStart) {
                long dur = range[1] - openStart;
                if (dur > MAX_SESSION_MS) dur = MAX_SESSION_MS;
                total += dur;
            }
        } catch (Exception e) {
            Log.e(TAG, "getTodayUsageMillis events error: " + e.getMessage(), e);
        }
        // 事件流对该包一条都没有时，退回 queryUsageStats 汇总（不含进行中会话，但聊胜于无）
        if (eventCount == 0) {
            long fallback = 0;
            try {
                List<UsageStats> stats = manager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, range[0], range[1]);
                if (stats != null) {
                    for (UsageStats s : stats) {
                        if (s != null && pkg.equals(s.getPackageName())) {
                            fallback += s.getTotalTimeInForeground();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "getTodayUsageMillis fallback error: " + e.getMessage(), e);
            }
            Log.w(TAG, "getTodayUsageMillis events empty for " + pkg
                    + ", fallback queryUsageStats=" + (fallback / 1000) + "s");
            total = fallback;
        }
        if (total < 0) total = 0;
        Log.d(TAG, "getTodayUsageMillis " + pkg + " events=" + eventCount
                + " total=" + (total / 1000) + "s");
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

        int totalEvents = 0;
        int pairedSessions = 0;
        UsageEvents events = manager.queryEvents(range[0], range[1]);
        if (events != null) {
            long start = 0;   // >0 目标包会话进行中（已 START 未熄屏）
            boolean seen = false; // 本流是否已为目标包起过会话（含跨零点半开兜底）
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                totalEvents++;
                int type = event.getEventType();
                long t = event.getTimeStamp();
                // 熄屏：结束当前会话（用户真正离开的信号）
                if (type == EVENT_SCREEN_OFF) {
                    if (start > 0) {
                        addTimelineEnd(template, items, start, t);
                        pairedSessions++;
                        start = 0;
                        seen = false;
                    }
                    continue;
                }
                if (type == EVENT_SCREEN_ON) continue;
                if (!target.equals(event.getPackageName())) continue;
                if (type == EVENT_START) {
                    if (start == 0) {
                        seen = true;
                        start = t;
                        AppItem fg = template.copy();
                        fg.mEventTime = t;
                        fg.mEventType = EVENT_START;
                        fg.mUsageTime = 0;
                        items.add(fg);
                    }
                } else if (type == EVENT_END_PAUSED || type == EVENT_END_STOPPED) {
                    // 退后台不结束会话；仅跨零点半开（本流未起过会话）时用 range[0] 兜起点
                    if (start == 0 && !seen) {
                        seen = true;
                        start = range[0];
                        AppItem fg = template.copy();
                        fg.mEventTime = start;
                        fg.mEventType = EVENT_START;
                        fg.mUsageTime = 0;
                        items.add(fg);
                    }
                }
            }
            // 边界收尾：查询结束仍未熄屏，结算到 range[1]
            if (start > 0 && range[1] > start) {
                addTimelineEnd(template, items, start, range[1]);
                pairedSessions++;
                start = 0;
            }
        } else {
            Log.w(TAG, "queryEvents returned null for target " + target);
        }
        // HyperOS/MIUI 兜底：无有效事件对时，用 queryUsageStats 合成一条会话，避免详情页空白
        if (pairedSessions == 0) {
            Log.w(TAG, "Timeline for " + target + " empty (events=" + totalEvents
                    + "); falling back to queryUsageStats");
            appendTargetFromUsageStats(manager, target, range, template, items);
        }
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
            // 会话起点：目标包 START；结束：熄屏(SCREEN_NON_INTERACTIVE)或查询边界。
            // 退后台(PAUSED/STOPPED)不结束会话——口径为“启动了没退出就一直计时”。
            Map<String, Long> startPoints = new HashMap<>();
            // 本事件流内已为该包起过会话（含跨 range[0] 半开兜底）的包，避免重复 START/半开
            Set<String> seenPkgs = new HashSet<>();
            Map<String, AppItem> itemsByPkg = new HashMap<>();
            int totalEvents = 0;
            int pairedSessions = 0;
            UsageEvents events = manager.queryEvents(range[0], range[1]);
            if (events == null) {
                Log.w(TAG, "queryEvents returned null for range: " + range[0] + " ~ " + range[1]);
            } else {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    totalEvents++;
                    int eventType = event.getEventType();
                    long eventTime = event.getTimeStamp();
                    String eventPackage = event.getPackageName();
                    // 熄屏（全局事件，无包名）：结束所有仍在进行的会话——“用户真正离开”信号
                    if (eventType == EVENT_SCREEN_OFF) {
                        Iterator<Map.Entry<String, Long>> it = startPoints.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<String, Long> se = it.next();
                            settleAppSession(itemsByPkg, se.getKey(), se.getValue(), eventTime);
                            pairedSessions++;
                        }
                        startPoints.clear();
                        seenPkgs.clear(); // 熄屏后允许重新起会话
                        continue;
                    }
                    if (eventType == EVENT_SCREEN_ON) continue;
                    if (TextUtils.isEmpty(eventPackage)) continue;
                    if (eventType == EVENT_START) {
                        // 只要没熄屏就只有一个进行中会话：已 open 时忽略重复 START（活动切换/闪屏）
                        if (!startPoints.containsKey(eventPackage)) {
                            startPoints.put(eventPackage, eventTime);
                            seenPkgs.add(eventPackage);
                            ensureAppItem(items, itemsByPkg, eventPackage);
                        }
                    } else if (eventType == EVENT_END_PAUSED || eventType == EVENT_END_STOPPED) {
                        // 退后台不结束会话；仅跨 range[0] 的半开（本流未起过会话）用 range[0] 兜起点
                        if (!startPoints.containsKey(eventPackage) && !seenPkgs.contains(eventPackage)) {
                            startPoints.put(eventPackage, range[0]);
                            seenPkgs.add(eventPackage);
                            ensureAppItem(items, itemsByPkg, eventPackage);
                        }
                    }
                }
                // 边界收尾：查询结束仍未熄屏的会话结算到 range[1]（now）
                Iterator<Map.Entry<String, Long>> sit = startPoints.entrySet().iterator();
                while (sit.hasNext()) {
                    Map.Entry<String, Long> entry = sit.next();
                    settleAppSession(itemsByPkg, entry.getKey(), entry.getValue(), range[1]);
                    pairedSessions++;
                }
            }
            // HyperOS/MIUI 兜底：queryEvents 拿不到任何有效会话时，改用 queryUsageStats
            if (pairedSessions == 0) {
                Log.w(TAG, "queryEvents produced no sessions (totalEvents=" + totalEvents
                        + "); falling back to queryUsageStats for HyperOS/MIUI compatibility");
                appendFromUsageStats(manager, range, items, itemsByPkg);
            }
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

    /**
     * 详情页：补一条“会话结束(┗)”行，时长 = 结束时刻 - 起始时刻，受 MAX_SESSION_MS 上限。
     */
    private void addTimelineEnd(AppItem template, List<AppItem> items, long start, long end) {
        AppItem bg = template.copy();
        bg.mEventTime = end;
        bg.mEventType = EVENT_END_PAUSED; // DetailActivity 据此渲染 ┗ 与时长行
        long dur = end - start;
        if (dur < 0) dur = 0;
        if (dur > MAX_SESSION_MS) dur = MAX_SESSION_MS;
        bg.mUsageTime = dur;
        if (bg.mUsageTime > AppConst.USAGE_TIME_MIX) bg.mCount = 1;
        items.add(bg);
    }

    private void ensureAppItem(List<AppItem> items, Map<String, AppItem> itemsByPkg, String pkg) {
        if (itemsByPkg.containsKey(pkg)) return;
        AppItem item = new AppItem();
        item.mPackageName = pkg;
        itemsByPkg.put(pkg, item);
        items.add(item);
    }

    /**
     * 结算一段会话：时长 = 结束 - 起始（受 MAX_SESSION_MS 上限），累加到该包。
     */
    private void settleAppSession(Map<String, AppItem> itemsByPkg, String pkg, long start, long end) {
        AppItem item = itemsByPkg.get(pkg);
        if (item == null) return;
        long dur = end - start;
        if (dur <= 0) return;
        if (dur > MAX_SESSION_MS) dur = MAX_SESSION_MS;
        item.mEventTime = end;
        item.mUsageTime += dur;
        if (dur > AppConst.USAGE_TIME_MIX) item.mCount++;
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
     * HyperOS/MIUI 兜底：queryEvents 未产出任何有效会话时，用 queryUsageStats 的
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
     * 详情页时间线的 HyperOS/MIUI 单包兜底：合成一条 "fg + bg" 会话，
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
