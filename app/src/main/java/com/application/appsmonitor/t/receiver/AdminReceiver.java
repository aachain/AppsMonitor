package com.application.appsmonitor.t.receiver;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.application.appsmonitor.t.service.UsageMonitorService;
import com.application.appsmonitor.t.util.MonitorAlarmUtil;

public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "设备管理员已启用，应用受保护无法卸载", Toast.LENGTH_LONG).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // 返回非空字符串时系统会弹出警告对话框，给用户一个拦截提示
        return "禁用设备管理员将导致应用时长监控失效，且应用可被卸载。确定要禁用吗？";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "设备管理员已禁用，应用不再受保护", Toast.LENGTH_SHORT).show();
        // 管理员被取消时，服务仍然继续运行，不依赖 DeviceAdmin 保活
    }

    @Override
    public void onPasswordChanged(Context context, Intent intent) {
        Toast.makeText(context, "密码已更改", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        Toast.makeText(context, "密码验证失败", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        Toast.makeText(context, "密码验证成功", Toast.LENGTH_SHORT).show();
    }
}
