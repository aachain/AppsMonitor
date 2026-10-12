package com.application.appsmonitor.t.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

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
import com.application.appsmonitor.t.util.PreferenceManager;

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
        mNotifier = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();
        startForeground(ONGOING_NOTIF_ID, buildOngoingNotification());
        mHandler = new Handler();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // 保证只挂一个轮询任务
        mHandler.removeCallbacks(mPoll);
        mHandler.post(mPoll);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mHandler != null) {
            mHandler.removeCallbacks(mPoll);
        }
        super.onDestroy();
    }

    private void checkAll() {
        String[] packages = AppConst.MONITOR_PACKAGES;
        if (packages == null || packages.length == 0) return;
        DataManager manager = DataManager.getInstance();
        if (manager == null || !manager.hasPermission(this)) return;

        String today = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        for (String pkg : packages) {
            long usedMs = manager.getTodayUsageMillis(this, pkg);
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
