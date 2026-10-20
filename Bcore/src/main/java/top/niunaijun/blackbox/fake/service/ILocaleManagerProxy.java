package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.os.LocaleList;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Proxy for android.app.ILocaleManager (Android 13+ / API 33+).
 * Prevents SecurityException when sandboxed apps (like Google Play Store) call
 * setApplicationLocales or getApplicationLocales without system permissions
 * (CHANGE_CONFIGURATION / READ_APP_SPECIFIC_LOCALES).
 */
public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";
    private static final String SERVICE_NAME = "locale";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE_NAME);
        if (binder == null) return null;
        try {
            Class<?> stubClass = Class.forName("android.app.ILocaleManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to get ILocaleManager stub", t);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "Suppressed setApplicationLocales to prevent SecurityException");
            return null;
        }
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return LocaleList.getEmptyLocaleList();
            } catch (Throwable t) {
                return null;
            }
        }
    }

    @ProxyMethod("getSystemLocales")
    public static class GetSystemLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return LocaleList.getDefault();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
