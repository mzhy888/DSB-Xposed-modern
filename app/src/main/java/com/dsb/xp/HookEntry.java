package com.dsb.xp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 现代 Xposed API（libxposed API 101）入口。
 *
 * 钩子点：框架里的 Activity.onResume / Activity.onCreate。
 * 不再去找宿主的 MainActivity 类，所以宿主有没有自己重写 onResume、类能不能在加载时找到，都不影响。
 * 真正挂面板前会在 GrabPanel.attach 里按「类名含 disubang」过滤。
 */
public class HookEntry extends XposedModule {
    static final String TAG = "DSB";
    static final String TARGET_PKG = "com.disubang.rider";

    private static volatile HookEntry instance;

    /** 写进 LSPosed 模块日志；框架还没就绪时退回 logcat。 */
    static void log(String msg) {
        HookEntry m = instance;
        try {
            if (m != null) {
                m.log(Log.INFO, TAG, msg);
                return;
            }
        } catch (Throwable ignored) {
        }
        Log.i(TAG, msg);
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        instance = this;
        log("module loaded in process " + param.getProcessName()
                + " | framework " + getFrameworkName() + " " + getFrameworkVersion()
                + " | api " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        log("package ready: " + param.getPackageName());
        hookResume();
        hookCreate();
    }

    private void hookResume() {
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            hook(m).intercept(chain -> {
                Object result = chain.proceed();
                attachTo(chain.getThisObject());
                return result;
            });
            log("hooked Activity.onResume");
        } catch (Throwable t) {
            log("hook Activity.onResume failed: " + t);
        }
    }

    private void hookCreate() {
        try {
            Method m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hook(m).intercept(chain -> {
                Object result = chain.proceed();
                attachTo(chain.getThisObject());
                return result;
            });
            log("hooked Activity.onCreate");
        } catch (Throwable t) {
            log("hook Activity.onCreate failed: " + t);
        }
    }

    private static void attachTo(Object self) {
        try {
            if (self instanceof Activity) {
                GrabPanel.get().attach((Activity) self);
            }
        } catch (Throwable t) {
            log("attach failed: " + t);
        }
    }
}
