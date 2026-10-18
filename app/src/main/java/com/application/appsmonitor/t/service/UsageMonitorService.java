package com.application.appsmonitor.t.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.application.appsmonitor.t.AppConst;
import com.application.appsmonitor.t.R;
import com.application.appsmonitor.t.data.DataManager;
import com.application.appsmonitor.t.ui.MainActivity;
import com.application.appsmonitor.t.util.AppUtil;
import com.application.appsmonitor.t.util.MonitorAlarmUtil;
import com.application.appsmonitor.t.util.PreferenceManager;
import com.application.appsmonitor.t.util.RouterUtil;

/**
 * UsageMonitorService
 * 常驻前台服务：每 {@link AppConst#MONITOR_CHECK_INTERVAL_MS} 轮询
 * {@link AppConst#MONITOR_PACKAGES} 中各应用的“当日累计前台时长”，
 * 达到 {@link AppConst#MONITOR_TIERS_MIN} 中某个档位时弹一条通知，
 * 同一应用同一天同一档位只提醒一次（未授予使用情况权限时静默跳过）。
 */
public class UsageMonitorService extends Service {

    private static final int ONGOING_NOTIF_ID = 1001;
    private static final String CHANNEL_ONGOING = "usage_monitor_ongoing";
    private static final String CHANNEL_ALERT = "usage_monitor_alert";

    /** 静态引用，用于外部判断服务是否存活 */
    private static volatile UsageMonitorService sInstance;

    public static boolean isRunning() {
        return sInstance != null;
    }

    private Handler mHandler;
    private NotificationManager mNotifier;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            try {
                checkAll();
            } catch (Exception e) {
                // 单次轮询异常不能影响服务存活
            }
            mHandler.postDelayed(this, AppConst.MONITOR_CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // 进程被闹钟/开机广播冷拉起时，MyApplication 一般已先行初始化；
        // 这里再兼容一次，防止个别时序下 getInstance() 返回 null
        if (DataManager.getInstance() == null) DataManager.init();
        mNotifier = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();
        startForeground(ONGOING_NOTIF_ID, buildOngoingNotification());
        mHandler = new Handler();
        // 引导用户把本应用加入电池优化白名单（HyperOS 后台冻结的首要对策）；
        // 未授权时跳一次设置页，用户确认后系统会记住，不再重复弹
        requestIgnoreBatteryOptimizations();
        // 调度看门狗闹钟 + JobScheduler（确保即使主闹钟链被清也能恢复）
        MonitorAlarmUtil.scheduleWatchdog(this);
        WatchdogJobService.schedule(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Handler 轮询只在进程存活且未被冻结时有效，保留作前台双保险
        mHandler.removeCallbacks(mPoll);
        mHandler.post(mPoll);
        // 关键：闹钟链交给系统 AlarmManager，无论 App 在前台/后台/被杀都能每分钟唤醒检查
        MonitorAlarmUtil.scheduleAlarm(this);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (mHandler != null) {
            mHandler.removeCallbacks(mPoll);
        }
        super.onDestroy();
        // 进程还活着时被杀：续排所有闹钟并尽量自拉起，保证后台提醒链不断；
        // 闹钟本身由系统保管，即使这里拉起失败，下一分钟 MonitorAlarmReceiver 仍会把服务带回来
        MonitorAlarmUtil.scheduleAlarm(this);
        MonitorAlarmUtil.scheduleWatchdog(this);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(new Intent(this, UsageMonitorService.class));
            } else {
                startService(new Intent(this, UsageMonitorService.class));
            }
        } catch (Exception e) {
            // 后台启动受限时静默，依赖闹钟链
        }
    }

    /**
     * 用户从最近任务划掉 App 时回调（配合 stopWithTask="false" 服务不会被立即销毁，
     * 但 MIUI/HyperOS 可能随后杀进程）。这里主动续排闹钟 + 延迟重拉自己，
     * 确保即使进程被回收，下一分钟闹钟仍会把服务带回来。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 续排闹钟——MIUI 划任务不一定清闹钟，但做双保险
        MonitorAlarmUtil.scheduleAlarm(this);
        MonitorAlarmUtil.scheduleWatchdog(this);
        // 延迟 1 秒后重启自身，给系统一点时间完成 task 移除动画
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(new Intent(UsageMonitorService.this, UsageMonitorService.class));
                    } else {
                        startService(new Intent(UsageMonitorService.this, UsageMonitorService.class));
                    }
                } catch (Exception e) {
                    // 静默，依赖闹钟兆底
                }
            }
        }, 1000L);
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Android 6+ 引导用户将本应用加入电池优化白名单。
     * HyperOS/MIUI 的“省电策略=无限制”底层就映射到这个设置；
     * 已被豁免时不重复打扰；跳设置页失败（个别 ROM 不支持该 action）则静默跳过。
     */
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // 无对应设置页等异常，不影响监控主流程
        }
    }

    private void checkAll() {
        String[] packages = AppConst.MONITOR_PACKAGES;
        if (packages == null || packages.length == 0) return;
        DataManager manager = DataManager.getInstance();
        if (manager == null || !manager.hasPermission(this)) return;

        String today = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        for (String pkg : packages) {
            // 心跳自采：只有目标应用在前台且屏幕亮时才开始累加时长，否则不计时
            long usedMs = updateHeartbeatUsage(pkg, today);
            int usedMin = (int) (usedMs / 60000L);
            for (int tier : AppConst.MONITOR_TIERS_MIN) {
                if (usedMin < tier) continue;
                String key = "mon_" + pkg + "_" + today + "_" + tier;
                if (PreferenceManager.getInstance().getBoolean(key)) continue;
                PreferenceManager.getInstance().putBoolean(key, true);
                int remaining = AppConst.MONITOR_DAILY_LIMIT_MIN - usedMin;
                if (remaining < 0) remaining = 0;
                postAlert(pkg, usedMin, remaining);
            }
            // 超上限（剩余 < 0）→ 通过路由器后台拉黑本设备，一天只触发一次
            if (AppConst.MONITOR_ROUTER_BLOCK_ON_OVERDUE
                    && usedMin > AppConst.MONITOR_DAILY_LIMIT_MIN) {
                String blockKey = "block_" + pkg + "_" + today;
                if (!PreferenceManager.getInstance().getBoolean(blockKey)) {
                    PreferenceManager.getInstance().putBoolean(blockKey, true);
                    triggerRouterBlock(pkg, usedMin);
                }
            }
        }
    }

    /**
     * 按“前台 + 亮屏”规则更新并返回目标包当日累计前台时长（毫秒）。
     * 规则：
     *  - 目标应用在前台 且 屏幕亮 → 累加距上次成功采样的实际间隔（封顶 maxDelta）；
     *  - 否则（切到别的 App / 退桌面 / 熄屏锁屏）→ 不计时，并重置计时基准点，
     *    使回到前台时从新的一刻起算，不会把空档时间算进来。
     *  - 首个采样点只记录时间戳不累加（避免把进入前台前的时间算入）。
     * 累计值与基准时间戳按“包名+日期”持久化，跨天自然从 0 重新计。
     */
    private long updateHeartbeatUsage(String pkg, String today) {
        DataManager dm = DataManager.getInstance();
        if (dm == null) return 0;
        long now = System.currentTimeMillis();
        boolean screenOn = dm.isScreenInteractive(this);
        boolean inForeground = pkg.equals(
                dm.getForegroundPackage(this, AppConst.MONITOR_FG_WINDOW_MS));

        String accKey = "hbacc_" + pkg + "_" + today;
        String tsKey = "hbts_" + pkg + "_" + today;
        PreferenceManager prefs = PreferenceManager.getInstance();
        long acc = prefs.getLong(accKey);
        long last = prefs.getLong(tsKey);

        if (screenOn && inForeground) {
            if (last == 0) {
                // 刚进入可计时状态：先立基准点，本拍不累加
                prefs.putLong(tsKey, now);
            } else {
                long delta = now - last;
                if (delta < 0) delta = 0;
                if (delta > AppConst.MONITOR_HEARTBEAT_MAX_DELTA_MS) {
                    delta = AppConst.MONITOR_HEARTBEAT_MAX_DELTA_MS;
                }
                acc += delta;
                prefs.putLong(accKey, acc);
                prefs.putLong(tsKey, now);
            }
        } else {
            // 不在前台或熄屏：不计时，重置基准点，回前台后从新时刻起算
            prefs.putLong(tsKey, 0);
        }
        return acc;
    }

    /**
     * 拉黑操作包含同步 HTTP 请求，必须离开主线程；失败后回滚当天去重标记，
     * 下一轮轮询还可以重试（例如路由器刚重启、瞬时不可达的情况）。
     */
    private void triggerRouterBlock(final String pkg, final int usedMin) {
        final String today = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        final String blockKey = "block_" + pkg + "_" + today;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RouterUtil.blockTargetDevice();
                if (!ok) {
                    PreferenceManager.getInstance().putBoolean(blockKey, false);
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        postBlockAlert(pkg, usedMin, ok);
                    }
                });
            }
        }).start();
    }

    private void postBlockAlert(String pkg, int usedMin, boolean success) {
        String appName = AppUtil.parsePackageName(getPackageManager(), pkg);
        String text = success
                ? getString(R.string.monitor_block_done, appName, usedMin)
                : getString(R.string.monitor_block_fail);

        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, ("blk_" + pkg).hashCode(), i, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(appName)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (mNotifier != null) {
            mNotifier.notify(("block_" + pkg).hashCode(), builder.build());
        }
    }

    private void postAlert(String pkg, int usedMin, int remaining) {
        String appName = AppUtil.parsePackageName(getPackageManager(), pkg);
        String text = getString(R.string.monitor_reminder, appName, usedMin, remaining);

        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, (pkg + usedMin).hashCode(), i, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(appName)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (mNotifier != null) {
            mNotifier.notify(("alert_" + pkg).hashCode(), builder.build());
        }
    }

    private Notification buildOngoingNotification() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, ONGOING_NOTIF_ID, i, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ONGOING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_message))
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = mNotifier;
            if (nm == null) return;
            NotificationChannel ongoing = new NotificationChannel(
                    CHANNEL_ONGOING, getString(R.string.notification_title),
                    NotificationManager.IMPORTANCE_LOW);
            ongoing.setShowBadge(false);
            nm.createNotificationChannel(ongoing);

            NotificationChannel alert = new NotificationChannel(
                    CHANNEL_ALERT, getString(R.string.monitor_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(alert);
        }
    }
}
