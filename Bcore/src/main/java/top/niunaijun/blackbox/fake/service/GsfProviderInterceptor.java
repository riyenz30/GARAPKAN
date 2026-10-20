package top.niunaijun.blackbox.fake.service;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IInterface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Intercepts ContentProvider calls to com.google.android.gsf.gservices
 * and returns the android_id from our own device checkin.
 * 
 * When Play Store queries content://com.google.android.gsf.gservices
 * with selectionArgs=["android_id"], we return the checkin android_id
 * stored at /data/user/0/{hostPkg}/files/gsf_android_id.
 */
public class GsfProviderInterceptor implements InvocationHandler {
    private static final String TAG = "GsfInterceptor";
    
    private final Object realProvider;
    
    private GsfProviderInterceptor(Object realProvider) {
        this.realProvider = realProvider;
    }
    
    /**
     * Wrap a host IContentProvider binder with our interceptor.
     */
    public static IInterface wrap(IInterface original) {
        if (original == null) return null;
        try {
            Class<?> icp = Class.forName("android.content.IContentProvider");
            Object proxy = Proxy.newProxyInstance(
                    icp.getClassLoader(),
                    new Class<?>[] { icp, IInterface.class },
                    new GsfProviderInterceptor(original));
            return (IInterface) proxy;
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to wrap GSF provider: " + t.getMessage());
            return original;
        }
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        
        // Forward asBinder() directly to real provider
        if ("asBinder".equals(name)) {
            if (realProvider instanceof IInterface) {
                return ((IInterface) realProvider).asBinder();
            }
        }
        
        // Intercept query() calls
        if ("query".equals(name)) {
            return handleQuery(method, args);
        }
        
        // Intercept call() — some Android versions use call("get_key", "android_id", ...)
        if ("call".equals(name)) {
            return handleCall(method, args);
        }
        
        // Everything else: delegate to real provider
        try {
            return method.invoke(realProvider, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
    
    private Object handleQuery(Method method, Object[] args) throws Throwable {
        // query(String callingPkg, [AttributionSource,] Uri url, String[] projection,
        //       Bundle queryArgs, ICancellationSignal cancel)
        // OR older: query(String callingPkg, Uri url, String[] projection, String selection,
        //                  String[] selectionArgs, String sortOrder, ICancellationSignal cancel)
        
        // Find selectionArgs: look for String[] in args
        String[] selectionArgs = null;
        for (Object arg : args) {
            if (arg instanceof String[]) {
                selectionArgs = (String[]) arg;
                break;
            }
        }
        
        // Also check for Bundle (Android 11+ bundled query args)
        if (selectionArgs == null) {
            for (Object arg : args) {
                if (arg instanceof Bundle) {
                    Bundle b = (Bundle) arg;
                    selectionArgs = b.getStringArray("android:query-arg-sql-selection-args");
                    if (selectionArgs == null) {
                        selectionArgs = b.getStringArray("android.content.extra.QUERY_ARG_SQL_SELECTION_ARGS");
                    }
                    break;
                }
            }
        }
        
        boolean wantsAndroidId = false;
        if (selectionArgs != null) {
            for (String s : selectionArgs) {
                if ("android_id".equals(s)) {
                    wantsAndroidId = true;
                    break;
                }
            }
        }
        
        // GSF gservices cursor format is ALWAYS 2 columns: { "name", "value" }
        // Play Store code calls cursor.getString(1) to get the value.
        // Returning a single-column cursor causes CursorIndexOutOfBoundsException.
        
        if (wantsAndroidId) {
            long androidId = readCheckinAndroidId();
            if (androidId != 0) {
                String decId = String.valueOf(androidId);
                Slog.w(TAG, "Intercepting gservices query for android_id, returning checkin id=" + decId + " (hex=" + Long.toHexString(androidId) + ")");
                MatrixCursor cursor = new MatrixCursor(new String[] { "name", "value" }, 1);
                cursor.addRow(new Object[] { "android_id", decId });
                return cursor;
            }
            Slog.w(TAG, "No checkin android_id found, falling back to empty 2-col cursor");
            // Return an empty 2-column cursor instead of delegating to host
            // (host provider fails on Android 16 due to UID mismatch)
            return new MatrixCursor(new String[] { "name", "value" }, 0);
        }
        
        // For non-android_id queries, try host first, fallback to empty 2-col cursor
        try {
            Object result = method.invoke(realProvider, args);
            // Verify the cursor has at least 2 columns; if not, wrap it
            if (result instanceof Cursor) {
                Cursor c = (Cursor) result;
                if (c.getColumnCount() < 2) {
                    Slog.w(TAG, "Host GSF cursor has " + c.getColumnCount() + " cols, expected 2; returning empty 2-col cursor");
                    c.close();
                    return new MatrixCursor(new String[] { "name", "value" }, 0);
                }
            }
            return result;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            Slog.w(TAG, "Host GSF query failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
            // Return empty 2-column cursor as safe fallback
            return new MatrixCursor(new String[] { "name", "value" }, 0);
        }
    }
    
    private Object handleCall(Method method, Object[] args) throws Throwable {
        // call(String callingPkg, [AttributionSource,] String authority, String method, String arg, Bundle extras)
        // Check if arg is "android_id"
        for (Object arg : args) {
            if ("android_id".equals(arg)) {
                long androidId = readCheckinAndroidId();
                if (androidId != 0) {
                    Slog.w(TAG, "Intercepting gservices call() for android_id, returning checkin id=" + Long.toHexString(androidId));
                    Bundle result = new Bundle();
                    result.putString("value", String.valueOf(androidId));
                    result.putString("android_id", String.valueOf(androidId));
                    return result;
                }
                break;
            }
        }
        
        // Delegate to real provider
        try {
            return method.invoke(realProvider, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            Slog.w(TAG, "Host GSF call() failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
            return new Bundle();
        }
    }
    
    private static long readCheckinAndroidId() {
        try {
            String hostPkg = BlackBoxCore.getHostPkg();
            File idFile = new File("/data/user/0/" + hostPkg + "/files/gsf_android_id");
            if (!idFile.exists()) {
                Slog.w(TAG, "gsf_android_id file not found at " + idFile.getAbsolutePath());
                return 0;
            }
            BufferedReader reader = new BufferedReader(new FileReader(idFile));
            String line = reader.readLine();
            reader.close();
            if (line != null && !line.isEmpty()) {
                return Long.parseLong(line.trim());
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to read gsf_android_id: " + t.getMessage());
        }
        return 0;
    }
}
