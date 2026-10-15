package com.application.appsmonitor.t.app;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import com.application.appsmonitor.t.AppConst;
import com.application.appsmonitor.t.BuildConfig;
import com.application.appsmonitor.t.data.AppItem;
import com.application.appsmonitor.t.data.DataManager;
import com.application.appsmonitor.t.db.DbHistoryExecutor;
import com.application.appsmonitor.t.db.DbIgnoreExecutor;
import com.application.appsmonitor.t.service.AppService;
import com.application.appsmonitor.t.service.UsageMonitorService;
import com.application.appsmonitor.t.service.WatchdogJobService;
import com.application.appsmonitor.t.util.CrashHandler;
import com.application.appsmonitor.t.util.MonitorAlarmUtil;
import com.application.appsmonitor.t.util.PreferenceManager;

/**
 * My Application
 * Created by zb on 18/12/2017.
 */

public class MyApplication extends Application {

    private ScreenStateReceiver mScreenStateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceManager.init(this);
        getApplicationContext().startService(new Intent(getApplicationContext(), AppService.class));
        ContextCompat.startForegroundService(this, new Intent(this, UsageMonitorService.class));
        DbIgnoreExecutor.init(getApplicationContext());
        DbHistoryExecutor.init(getApplicationContext());
        DataManager.init();
        addDefaultIgnoreAppsToDB();
        if (AppConst.CRASH_TO_FILE) CrashHandler.getInstance().init();
        // 调度看门狗闹钟 + JobScheduler 周期任务
        MonitorAlarmUtil.scheduleWatchdog(this);
        WatchdogJobService.schedule(this);
        // 动态注册屏幕点亮广播，作为额外心跳检查服务存活
        registerScreenStateReceiver();
    }

    private void addDefaultIgnoreAppsToDB() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> mDefaults = new ArrayList<>();
                mDefaults.add("com.android.settings");
                mDefaults.add(BuildConfig.APPLICATION_ID);
                for (String packageName : mDefaults) {
                    AppItem item = new AppItem();
                    item.mPackageName = packageName;
                    item.mEventTime = System.currentTimeMillis();
                    DbIgnoreExecutor.getInstance().insertItem(item);
                }
            }
        }).run();
    }

    private void registerScreenStateReceiver() {
        mScreenStateReceiver = new ScreenStateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mScreenStateReceiver, filter);
    }

    /**
     * 屏幕点亮 / 解锁时检查前台服务是否存活，
     * 如果被系统杀了则重新拉起并续排闹钟。
     * 注意：ACTION_SCREEN_ON / ACTION_USER_PRESENT 在 Android 8+ 属于限制广播，
     * 只能动态注册，不能写在清单中。
     */
    private static class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!UsageMonitorService.isRunning()) {
                MonitorAlarmUtil.scheduleAlarm(context);
                MonitorAlarmUtil.scheduleWatchdog(context);
                WatchdogJobService.schedule(context);
                ContextCompat.startForegroundService(context,
                        new Intent(context, UsageMonitorService.class));
            }
        }
    }
}
