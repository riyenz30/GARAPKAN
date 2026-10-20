package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.Collections;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;

/**
 * Proxy for the "crossprofileapps" system service.
 *
 * Play Store (com.android.vending) calls CrossProfileApps.getTargetUserProfiles()
 * which triggers ICrossProfileApps.getTargetUserProfiles(callingPackage).
 * Inside BlackBox the callingPackage is "com.android.vending" but the real UID
 * belongs to the host app, causing:
 *   SecurityException: Package com.android.vending does not belong to <hostUid>
 *
 * We intercept the binder and return safe defaults so the call never reaches the
 * real OS service.
 */
public class ICrossProfileAppsProxy extends BinderInvocationStub {
    private static final String SERVICE_NAME = "crossprofileapps";

    public ICrossProfileAppsProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        IBinder service = BRServiceManager.get().getService(SERVICE_NAME);
        if (service == null) {
            return null;
        }
        try {
            Class<?> stubClass = Class.forName("android.content.pm.ICrossProfileApps$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, service);
        } catch (Throwable t) {
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

    @Override
    protected void onBindMethod() {
        super.onBindMethod();

        // getTargetUserProfiles(String callingPackage) -> empty list
        addMethodHook(new MethodHook() {
            @Override
            protected String getMethodName() {
                return "getTargetUserProfiles";
            }

            @Override
            protected Object hook(Object who, Method method, Object[] args) throws Throwable {
                return Collections.emptyList();
            }
        });

        // canRequestInteractAcrossProfiles(String callingPackage) -> false
        addMethodHook(new MethodHook() {
            @Override
            protected String getMethodName() {
                return "canRequestInteractAcrossProfiles";
            }

            @Override
            protected Object hook(Object who, Method method, Object[] args) throws Throwable {
                return false;
            }
        });

        // canInteractAcrossProfiles(String callingPackage) -> false
        addMethodHook(new MethodHook() {
            @Override
            protected String getMethodName() {
                return "canInteractAcrossProfiles";
            }

            @Override
            protected Object hook(Object who, Method method, Object[] args) throws Throwable {
                return false;
            }
        });
    }
}
