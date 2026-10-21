package top.niunaijun.blackbox.fake.service;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.util.BRSingleton;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.BActivityManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ScanClass;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.TaskDescriptionCompat;


@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityClientProxy extends ClassInvocationStub {
    public static final String TAG = "IActivityClientProxy";
    private final Object who;

    public IActivityClientProxy(Object who) {
        this.who = who;
    }

    @Override
    protected Object getWho() {
        if (who != null) {
            return who;
        }
        Object instance = BRActivityClient.get().getInstance();
        Object singleton = BRActivityClient.get(instance).INTERFACE_SINGLETON();
        return BRSingleton.get(singleton).get();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        Object instance = BRActivityClient.get().getInstance();
        Object singleton = BRActivityClient.get(instance).INTERFACE_SINGLETON();
        BRSingleton.get(singleton)._set_mInstance(proxyInvocation);
        try {
            BRActivityClientActivityClientControllerSingleton.get(singleton)._set_mKnownInstance(proxyInvocation);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to set mKnownInstance in inject(): " + t.getMessage());
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (!name.equals("asBinder") && !name.equals("toString") && !name.equals("hashCode") && !name.equals("equals")) {
            Slog.w(TAG, "IActivityClientProxy.invoke: " + name);
        }
        return super.invoke(proxy, method, args);
    }

    @Override
    public Object getProxyInvocation() {
        return super.getProxyInvocation();
    }

    @Override
    public void onlyProxy(boolean o) {
        super.onlyProxy(o);
    }

    @ProxyMethod("finishActivity")
    public static class FinishActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBinder token = (IBinder) args[0];
            int resultCode = 0;
            Intent resultData = null;
            try {
                if (args.length > 1 && args[1] instanceof Integer) resultCode = (int) args[1];
                if (args.length > 2 && args[2] instanceof Intent) resultData = (Intent) args[2];
            } catch (Throwable ignored) {}
            String extrasSummary = "none";
            if (resultData != null) {
                try {
                    android.os.Bundle extras = resultData.getExtras();
                    if (extras != null) {
                        StringBuilder sb = new StringBuilder();
                        for (String key : extras.keySet()) {
                            try {
                                sb.append(key).append("=").append(extras.get(key)).append(", ");
                            } catch (Throwable t) {
                                sb.append(key).append("=<unreadable>, ");
                            }
                        }
                        extrasSummary = sb.toString();
                    }
                } catch (Throwable t) {
                    extrasSummary = "unreadable: " + t.getMessage();
                }
            }
            Slog.w(TAG, "finishActivity: pkg=" + BActivityThread.getAppPackageName()
                    + " resultCode=" + resultCode
                    + " data=" + (resultData != null ? resultData.toUri(0) : "null")
                    + " extras=[" + extrasSummary + "]");
            BActivityManager.get().onFinishActivity(token);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityResumed")
    public static class ActivityResumed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBinder token = (IBinder) args[0];
            BActivityManager.get().onActivityResumed(token);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityDestroyed")
    public static class ActivityDestroyed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBinder token = (IBinder) args[0];
            BActivityManager.get().onActivityDestroyed(token);
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            args[1] = TaskDescriptionCompat.fix(td);
            return method.invoke(who, args);
        }
    }

    /**
     * Hook getLaunchedFromPackage so GMS sees the virtual caller, not the host stub.
     * On Android 12+, Activity.getLaunchedFromPackage() goes through IActivityClientController.
     * Without this hook, GMS SignInActivity sees com.dualspace.clone as the caller and
     * returns DEVELOPER_ERROR because that package has no Google API project configured.
     */
    @ProxyMethod("getLaunchedFromPackage")
    public static class GetLaunchedFromPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IBinder token = (IBinder) args[0];
            String callingPkg = BlackBoxCore.getBActivityManager()
                    .getCallingPackage(token, BActivityThread.getUserId());
            String hostPkg = BlackBoxCore.getHostPkg();
            if (callingPkg != null && !callingPkg.equals(hostPkg)) {
                Slog.w(TAG, "getLaunchedFromPackage: returning virtual caller=" + callingPkg);
                return callingPkg;
            }
            // If we couldn't find a virtual caller, use the current app's package
            String appPkg = BActivityThread.getAppPackageName();
            if (appPkg != null) {
                Slog.w(TAG, "getLaunchedFromPackage: fallback to appPkg=" + appPkg);
                return appPkg;
            }
            // Last resort: delegate to real system
            Slog.w(TAG, "getLaunchedFromPackage: delegating to real system");
            return method.invoke(who, args);
        }
    }

    /**
     * Hook getLaunchedFromUid so GMS sees the virtual UID, not the host UID.
     */
    @ProxyMethod("getLaunchedFromUid")
    public static class GetLaunchedFromUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int bUid = BActivityThread.getBUid();
            Slog.w(TAG, "getLaunchedFromUid: returning virtual uid=" + bUid);
            return bUid;
        }
    }
}
