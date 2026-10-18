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
        long openStart = 0;
        boolean seen = false;
        int eventCount = 0;
        try {
            UsageEvents events = manager.queryEvents(range[0], range[1]);
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    if (!pkg.equals(event.getPackageName())) continue;
                    eventCount++;
                    int type = event.getEventType();
                    long t = event.getTimeStamp();
                    if (type == EVENT_START) {
                        // 只跟踪一个进行中的会话：已 open 时忽略重复 START
                        if (openStart == 0) {
                            seen = true;
                            openStart = t;
                        }
                    } else if (type == EVENT_END_PAUSED || type == EVENT_END_STOPPED) {
                        if (openStart == 0) {
                            // 找不到配对 START：本流已见过 START → 是同一次退出的重复 END（PAUSED/STOPPED 双发），跳过
                            if (seen) continue;
                            // 本流从未出现 START 的 END：跨零点半开会话，用 range[0] 兜住起点
                            seen = true;
                            openStart = range[0];
                        }
                        long dur = t - openStart;
                        if (dur < 0) dur = 0;
                        if (dur > MAX_SESSION_MS) dur = MAX_SESSION_MS;
                        total += dur;
                        openStart = 0;
                    }
                }
            }
            // 结算仍在前台的当前会话（这正是 queryUsageStats 读不到的那一段）
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
            long start = 0;
            // 目标 app 是否已在本流内出现 START 或已做过一次半开兑底
            boolean seen = false;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                totalEvents++;
                if (!target.equals(event.getPackageName())) continue;
                int type = event.getEventType();
                long t = event.getTimeStamp();
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
                    // start==0 且已见过 START，却又收到无配对 START 的 END → 同一次退出的
                    // 重复 END（PAUSED/STOPPED 双发），跳过，否则会当跨零点半开会话用 range[0] 兜出幽灵时长
                    if (start == 0 && seen) {
                        continue;
                    }
                    // 半开 session：目标 app 的 START 落在 range[0] 之前，用 range[0] 兜住起点
                    long s = start > 0 ? start : range[0];
                    if (t > s) {
                        if (start == 0) {
                            seen = true;
                            // 补一条合成的“前台开始”行，保持详情页成对展示
                            AppItem fg = template.copy();
                            fg.mEventTime = s;
                            fg.mEventType = EVENT_START;
                            fg.mUsageTime = 0;
                            items.add(fg);
                        }
                        AppItem bg = template.copy();
                        bg.mEventTime = t;
                        bg.mEventType = EVENT_END_PAUSED; // 保持与 DetailActivity 现有渲染逻辑一致
                        long dur = t - s;
                        if (dur > MAX_SESSION_MS) dur = MAX_SESSION_MS;
                        bg.mUsageTime = dur;
                        if (bg.mUsageTime > AppConst.USAGE_TIME_MIX) bg.mCount = 1;
                        items.add(bg);
                        pairedSessions++;
                        start = 0;
                    }
                }
            }
            // 边界收尾：目标应用仍“前台”则用 range[1] 结算；上限 MAX_SESSION_MS
            // 避免“忘记关的前台应用”把整天数据高估
            if (start > 0 && range[1] > start) {
                AppItem bg = template.copy();
                bg.mEventTime = range[1];
                bg.mEventType = EVENT_END_PAUSED;
                long dur = range[1] - start;
                if (dur > MAX_SESSION_MS) dur = MAX_SESSION_MS;
                bg.mUsageTime = dur;
                if (bg.mUsageTime > AppConst.USAGE_TIME_MIX) bg.mCount = 1;
                items.add(bg);
                pairedSessions++;
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
            Map<String, Long> startPoints = new HashMap<>();
            // 本事件流内已出现过 START（或已做过一次半开兑底）的包；
            // 后续“找不到配对 START”的 END 都是重复事件，必须丢弃
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
                    if (TextUtils.isEmpty(eventPackage)) continue;
                    if (eventType == EVENT_START) {
                        // 标记该包本流内已 START；同时作为“重复 END”去重依据
                        seenPkgs.add(eventPackage);
                        // HyperOS 平板多窗口常见：新应用 RESUMED 到达但旧应用的 END/PAUSED/STOPPED 漏发。
                        // 先把除当前 pkg 外仍挂在 startPoints 里的“孤儿会话”用当前时刻隐式结算。
                        Iterator<Map.Entry<String, Long>> it = startPoints.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<String, Long> oe = it.next();
                            String orphanPkg = oe.getKey();
                            if (orphanPkg.equals(eventPackage)) continue;
                            long orphanStart = oe.getValue();
                            if (eventTime > orphanStart) {
                                AppItem oItem = itemsByPkg.get(orphanPkg);
                                if (oItem != null) {
                                    long d = eventTime - orphanStart;
                                    if (d > MAX_SESSION_MS) d = MAX_SESSION_MS;
                                    oItem.mEventTime = eventTime;
                                    oItem.mUsageTime += d;
                                    if (d > AppConst.USAGE_TIME_MIX) oItem.mCount++;
                                    pairedSessions++;
                                }
                                it.remove();
                            }
                        }
                        AppItem item = itemsByPkg.get(eventPackage);
                        if (item == null) {
                            item = new AppItem();
                            item.mPackageName = eventPackage;
                            itemsByPkg.put(eventPackage, item);
                            items.add(item);
                        }
                        if (!startPoints.containsKey(eventPackage)) {
                            startPoints.put(eventPackage, eventTime);
                        }
                    } else if (eventType == EVENT_END_PAUSED || eventType == EVENT_END_STOPPED) {
                        Long startObj = startPoints.remove(eventPackage);
                        if (startObj == null) {
                            // 没有配对 START：若本流内已见过该包 START，则为重复 END
                            // （PAUSED/STOPPED 双发，或包切换 cascade 已结算后延迟到达的后台事件），
                            // 必须跳过，否则会当跨零点半开会话用 range[0] 兜出幽灵时长（如 0:00~2:00）。
                            if (seenPkgs.contains(eventPackage)) {
                                continue;
                            }
                            // 本流内从未出现 START 的 END：真正跨 range[0] 的半开会话（如昨日 23:00 开启跨过 00:00），
                            // 标记后用 range[0] 兑底，后续重复 END 会被上面的分支丢弃
                            seenPkgs.add(eventPackage);
                        }
                        // 半开 session：END 落在窗口内但 START 在窗口之前（跨 range[0]），
                        // 用 range[0] 兜住起点，避免整段使用被丢弃。
                        long start = startObj != null ? startObj : range[0];
                        if (eventTime > start) {
                            AppItem item = itemsByPkg.get(eventPackage);
                            if (item == null) {
                                item = new AppItem();
                                item.mPackageName = eventPackage;
                                itemsByPkg.put(eventPackage, item);
                                items.add(item);
                            }
                            long duration = eventTime - start;
                            if (duration > MAX_SESSION_MS) duration = MAX_SESSION_MS;
                            item.mEventTime = eventTime;
                            item.mUsageTime += duration;
                            if (duration > AppConst.USAGE_TIME_MIX) item.mCount++;
                            pairedSessions++;
                        }
                    }
                }
                // 边界收尾：查询结束仍在“开始”状态的应用（HyperOS 与平板多窗口常见），
                // 用 range[1] 结算；上限 MAX_SESSION_MS 避免“忘关的前台应用”高估整天数据
                for (Map.Entry<String, Long> entry : startPoints.entrySet()) {
                    AppItem item = itemsByPkg.get(entry.getKey());
                    if (item == null) continue;
                    long duration = range[1] - entry.getValue();
                    if (duration <= 0) continue;
                    if (duration > MAX_SESSION_MS) duration = MAX_SESSION_MS;
                    item.mEventTime = range[1];
                    item.mUsageTime += duration;
                    if (duration > AppConst.USAGE_TIME_MIX) item.mCount++;
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
