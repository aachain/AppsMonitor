package com.application.appsmonitor.t.data;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
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
    private static final int EVENT_START = UsageEvents.Event.MOVE_TO_FOREGROUND;
    private static final int EVENT_END_PAUSED = UsageEvents.Event.MOVE_TO_BACKGROUND;

    // ===== 方案A 心跳计数持久化键 =====
    // 上一次心跳 tick 的时间戳（全局，用于计算经过时间 delta）
    private static final String PREF_HB_TICK = "hb_tick";
    // 最近一次观测到的前台包（全局持久化，游戏静默空窗靠它延续）
    private static final String PREF_HB_FG = "hb_fg";
    // 按天+包累计的心跳前台时长前缀，完整键为 hbms_yyyyMMdd_包名
    private static final String HB_TOTAL_PREFIX = "hbms_";

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
     * 口径（方案A）：主统计源为前台轮询心跳计数（{@link #heartbeatTick}），
     * 系统 bucket（queryUsageStats 的 totalTimeInForeground）降为兜底，
     * 取二者最大值：游戏场景心跳远大于 bucket（bucket 漏记），常规场景 bucket
     * 可补齐心跳因服务被杀而漏掉的已结束会话。
     */
    public long getTodayUsageMillis(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) return 0;
        long hb = heartbeatTodayMillis(pkg);
        long bucket = bucketTodayMillis(context, pkg);
        long result = Math.max(hb, bucket);
        Log.d(TAG, "getTodayUsageMillis " + pkg + " hb=" + (hb / 1000) + "s bucket="
                + (bucket / 1000) + "s -> " + (result / 1000) + "s");
        return result;
    }

    /** 系统 bucket 当日前台时长（兜底路径），未授权或异常返回 0。 */
    private long bucketTodayMillis(Context context, String pkg) {
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
            Log.e(TAG, "bucketTodayMillis error: " + e.getMessage(), e);
        }
        if (total < 0) total = 0;
        return total;
    }

    /** 读取指定包今日心跳累计（毫秒）。 */
    public long heartbeatTodayMillis(String pkg) {
        if (TextUtils.isEmpty(pkg)) return 0;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        return PreferenceManager.getInstance().getLong(HB_TOTAL_PREFIX + today + "_" + pkg, 0L);
    }

    /**
     * 心跳一次 tick：由前台服务的轮询/闹钟链驱动（见 UsageMonitorService.checkAll）。
     * 计算距上次 tick 的经过时间 delta（封顶 HB_MAX_TICK_MS，跨天/首次为 0），
     * 屏幕熄灭时不累加（视为用户离开），否则探测当前前台包并把 delta 累加到其当日计数。
     */
    public void heartbeatTick(Context context) {
        long now = System.currentTimeMillis();
        PreferenceManager pm = PreferenceManager.getInstance();
        long last = pm.getLong(PREF_HB_TICK, 0L);
        long delta = 0L;
        if (last > 0L && isSameDay(last, now)) {
            delta = now - last;
            if (delta > AppConst.HB_MAX_TICK_MS) delta = AppConst.HB_MAX_TICK_MS;
            if (delta < 0L) delta = 0L;
        }
        pm.putLong(PREF_HB_TICK, now);
        // 首次/跨天/无间隔：仅建立基线，不累加
        if (delta <= 0L) return;
        // 熄屏视为离开，不计时（延续既有“熄灭即结束会话”口径）
        if (!isScreenInteractive(context)) return;
        String fg = detectForegroundPackage(context, now);
        if (TextUtils.isEmpty(fg) || fg.equals(context.getPackageName())) return;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date(now));
        String key = HB_TOTAL_PREFIX + today + "_" + fg;
        long acc = pm.getLong(key, 0L) + delta;
        pm.putLong(key, acc);
        Log.d(TAG, "heartbeatTick fg=" + fg + " +" + (delta / 1000) + "s acc=" + (acc / 1000) + "s");
    }

    /**
     * 探测“当前前台包”。两层信号：
     * 1) ActivityManager.getRunningTasks(1)：MIUI/HyperOS 对持 usage-access 的 App
     *    常不强制 AOSP 限制，能拿到真实顶层任务；返回自身包名视为不可用，转下层。
     * 2) queryEvents 回看窗口内最后一条 RESUMED 的包；若窗口内无任何事件（游戏静默
     *    空窗）则沿用持久化的 last-known 前台包——真实离开必触发他包 RESUMED 覆盖它，
     *    故“无新事件 = 前台未变”成立。
     */
    private String detectForegroundPackage(Context context, long now) {
        String own = context.getPackageName();
        // 层一：getRunningTasks
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty()) {
                    ComponentName top = tasks.get(0).topActivity;
                    if (top != null) {
                        String p = top.getPackageName();
                        if (!TextUtils.isEmpty(p) && !p.equals(own)) {
                            PreferenceManager.getInstance().putString(PREF_HB_FG, p);
                            return p;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // 部分 ROM 限制/异常，落到层二
        }
        // 层二：queryEvents 回看窗口内最后一条 RESUMED
        String lastKnown = PreferenceManager.getInstance().getString(PREF_HB_FG);
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager != null) {
            try {
                long from = now - AppConst.HB_FG_LOOKBACK_MS;
                UsageEvents events = manager.queryEvents(from, now);
                if (events != null) {
                    UsageEvents.Event e = new UsageEvents.Event();
                    String seen = null;
                    while (events.hasNextEvent()) {
                        events.getNextEvent(e);
                        if (e.getEventType() == EVENT_START && !TextUtils.isEmpty(e.getPackageName())) {
                            seen = e.getPackageName();
                        }
                    }
                    if (!TextUtils.isEmpty(seen) && !seen.equals(own)) {
                        lastKnown = seen;
                        PreferenceManager.getInstance().putString(PREF_HB_FG, seen);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "detectForegroundPackage queryEvents error: " + t.getMessage());
            }
        }
        if (!TextUtils.isEmpty(lastKnown) && !lastKnown.equals(own)) return lastKnown;
        return null;
    }

    private boolean isScreenInteractive(Context context) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                return pm.isInteractive();
            }
            return pm.isScreenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isSameDay(long a, long b) {
        String da = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date(a));
        String db = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date(b));
        return da.equals(db);
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

        // 主统计源为心跳、系统 bucket 降为兜底：详情页无逐次时间线，合成一段会话，
        // 当日时长按 max(心跳, bucket) 取值，与实时监控口径一致。
        long hbMillis = (offset == SortEnum.TODAY.ordinal()) ? heartbeatTodayMillis(target) : 0L;
        appendTargetFromUsageStats(manager, target, range, template, items, hbMillis);
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
            // 系统 bucket 作兜底按包聚合；主统计源为心跳（仅当日区间可比）。
            Map<String, AppItem> itemsByPkg = new HashMap<>();
            appendFromUsageStats(manager, range, items, itemsByPkg);
            if (offset == SortEnum.TODAY.ordinal()) {
                applyHeartbeat(items, itemsByPkg);
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

    /**
     * 将当日心跳时长合入总列表：对已有包取 max(心跳, bucket)；若某监控包在 bucket
     * 缺失但心跳有值（如 HyperOS 游戏 bucket 几近 0），补建条目，避免主路径漏显。
     */
    private void applyHeartbeat(List<AppItem> items, Map<String, AppItem> itemsByPkg) {
        for (AppItem item : items) {
            long hb = heartbeatTodayMillis(item.mPackageName);
            if (hb > item.mUsageTime) {
                item.mUsageTime = hb;
                if (item.mCount <= 0) item.mCount = 1;
            }
        }
        String[] monitored = AppConst.MONITOR_PACKAGES;
        if (monitored != null) {
            for (String pkg : monitored) {
                if (TextUtils.isEmpty(pkg) || itemsByPkg.containsKey(pkg)) continue;
                long hb = heartbeatTodayMillis(pkg);
                if (hb <= 0) continue;
                AppItem item = new AppItem();
                item.mPackageName = pkg;
                item.mUsageTime = hb;
                item.mCount = 1;
                itemsByPkg.put(pkg, item);
                items.add(item);
            }
        }
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
     * 详情页时间线（基于系统 bucket + 心跳）：合成一条 "fg + bg" 会话，
     * 时长取 max(bucket, hbMillis)，时间基于 UsageStats.lastTimeUsed 与时长反推。
     */
    private void appendTargetFromUsageStats(UsageStatsManager usm, String target,
                                            long[] range, AppItem template,
                                            List<AppItem> items, long hbMillis) {
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
            // 心跳为主：取更大者作为真实当日时长；last 不可用时以查询边界兜底。
            if (hbMillis > dur) dur = hbMillis;
            if (dur <= 0) {
                Log.w(TAG, "appendTargetFromUsageStats empty for target " + target);
                return;
            }
            if (last <= 0) last = range[1];
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
