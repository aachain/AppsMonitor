package com.application.appsmonitor.t.app;

import android.app.Application;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import com.application.appsmonitor.t.AppConst;
import com.application.appsmonitor.t.BuildConfig;
import com.application.appsmonitor.t.data.AppItem;
import com.application.appsmonitor.t.data.DataManager;
import com.application.appsmonitor.t.db.DbHistoryExecutor;
import com.application.appsmonitor.t.db.DbIgnoreExecutor;
import com.application.appsmonitor.t.service.AppService;
import com.application.appsmonitor.t.util.CrashHandler;
import com.application.appsmonitor.t.util.PreferenceManager;

/**
 * My Application
 * Created by zb on 18/12/2017.
 */

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceManager.init(this);
        getApplicationContext().startService(new Intent(getApplicationContext(), AppService.class));
        DbIgnoreExecutor.init(getApplicationContext());
        DbHistoryExecutor.init(getApplicationContext());
        DataManager.init();
        addDefaultIgnoreAppsToDB();
        if (AppConst.CRASH_TO_FILE) CrashHandler.getInstance().init();
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
}
