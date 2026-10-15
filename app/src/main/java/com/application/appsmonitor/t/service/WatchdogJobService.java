package com.application.appsmonitor.t.service;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.application.appsmonitor.t.AppConst;
import com.application.appsmonitor.t.util.MonitorAlarmUtil;

/**
 * WatchdogJobService
 * 基于 JobScheduler 的第三道保活防线，与 AlarmManager 完全独立的调度系统。
 * 即使 MIUI 清除了全部闹钟，JobScheduler 任务仍由系统保持，
 * 周期触发时检查主服务是否存活，必要时拉起。
 * 注意：Android 7.0+ 周期任务最小间隔 15 分钟。
 */
public class WatchdogJobService extends JobService {

    /**
     * 调度看门狗 Job（幂等，已存在则替换）。
     */
    public static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(AppConst.WATCHDOG_JOB_ID,
                new ComponentName(ctx, WatchdogJobService.class))
                .setPeriodic(15 * 60 * 1000L) // 15 分钟（系统最小值）
                .setPersisted(true)            // 跨重启保留（需 RECEIVE_BOOT_COMPLETED）
                .build();
        js.schedule(job);
    }

    /**
     * 取消看门狗 Job。
     */
    public static void cancel(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(AppConst.WATCHDOG_JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // 检查主服务存活
        if (!UsageMonitorService.isRunning()) {
            ContextCompat.startForegroundService(this,
                    new Intent(this, UsageMonitorService.class));
            MonitorAlarmUtil.scheduleAlarm(this);
            MonitorAlarmUtil.scheduleWatchdog(this);
        }
        return false; // 无后台工作
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // 要求系统重新调度
    }
}
