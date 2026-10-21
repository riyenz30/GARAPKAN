package top.niunaijun.blackbox.core;


import android.os.Binder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;

import top.niunaijun.blackbox.core.system.JarManager;
import top.niunaijun.blackbox.utils.Slog;


public class NativeCore {
    public static final String TAG = "NativeCore";
    private static final Map<Integer, Integer> sPidUidCache = new ConcurrentHashMap<>();

    static {

        System.loadLibrary("blackbox");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    public static native boolean disableHiddenApi();
    
    public static native boolean disableResourceLoading();


    @Keep
    public static int getCallingUid(int origCallingUid) {
        try {
            // System UIDs — never remap
            if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
                return origCallingUid;
            if (origCallingUid > Process.LAST_APPLICATION_UID)
                return origCallingUid;

            if (origCallingUid != BlackBoxCore.getHostUid()) {
                // Not the host UID — some external app calling us, pass through
                return origCallingUid;
            }

            // origCallingUid == hostUid — this is either:
            // (a) another sandbox process calling us (intra-sandbox IPC)
            // (b) a call within our own process (self-call)
            // (c) a call from the real Android system using our UID

            int callingPid = Binder.getCallingPid();

            // Case (b): self-call (no incoming transaction, or same-process call)
            // Must return hostUid here because Android 12+ AttributionSource checks:
            //   Binder.getCallingUid() == AttributionSource.uid (= Process.myUid() = hostUid)
            // Returning a virtual UID here crashes every ContentProvider query to system
            // providers (Settings, Gservices, Phenotype).
            if (callingPid == Process.myPid()) {
                return BlackBoxCore.getHostUid();
            }

            // Case (a): check if callingPid belongs to a sandbox process
            // Use cache to avoid IPC on every Binder.getCallingUid() call
            Integer cached = sPidUidCache.get(callingPid);
            if (cached != null) {
                return cached;
            }

            // Query the server for PID→virtual UID mapping
            try {
                int vUid = BlackBoxCore.getBActivityManager().getUidByPid(callingPid);
                if (vUid > 0) {
                    sPidUidCache.put(callingPid, vUid);
                    return vUid;
                }
            } catch (Throwable t) {
                // Server not ready or IPC failed — fall through
            }

            // Case (c): external system caller with same UID, or unknown PID
            // Return hostUid to satisfy AttributionSource.enforceCallingUid()
            sPidUidCache.put(callingPid, BlackBoxCore.getHostUid());
            return BlackBoxCore.getHostUid();
        } catch (Throwable e) {
            Log.e(TAG, "Error in getCallingUid: " + e.getMessage());
            return origCallingUid;
        }
    }

    /**
     * Clear the PID→UID cache when a process dies or restarts.
     * Called from BProcessManagerService when a process record is removed.
     */
    public static void invalidatePidUidCache(int pid) {
        sPidUidCache.remove(pid);
    }

    /**
     * Clear the entire PID→UID cache.
     */
    public static void clearPidUidCache() {
        sPidUidCache.clear();
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            File emptyJar = JarManager.getInstance().getEmptyJar();
            if (emptyJar == null) {
                Log.w(TAG, "Empty JAR not available, attempting sync initialization");
                JarManager.getInstance().initializeSync();
                emptyJar = JarManager.getInstance().getEmptyJar();
            }
            
            if (emptyJar == null || !emptyJar.exists()) {
                Log.e(TAG, "Empty JAR file not found or invalid");
                return new long[]{};
            }
            
            DexFile dexFile = new DexFile(emptyJar);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            Log.d(TAG, "Successfully loaded empty DEX with " + cookies.size() + " cookies");
            return longs;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load empty DEX", e);
        }
        return new long[]{};
    }
    
    
    private static long[] createFallbackEmptyDex() {
        try {
            Slog.d(TAG, "Creating fallback empty DEX");
            
            
            
            byte[] emptyDexBytes = createMinimalDexBytes();
            
            
            File tempDexFile = File.createTempFile("fallback_empty", ".dex");
            tempDexFile.deleteOnExit();
            
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempDexFile);
            fos.write(emptyDexBytes);
            fos.close();
            
            
            DexFile dexFile = new DexFile(tempDexFile);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            
            if (cookies != null && !cookies.isEmpty()) {
                long[] longs = new long[cookies.size()];
                for (int i = 0; i < cookies.size(); i++) {
                    longs[i] = cookies.get(i);
                }
                
                Slog.d(TAG, "Successfully created fallback empty DEX with " + cookies.size() + " cookies");
                return longs;
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error creating fallback empty DEX: " + e.getMessage());
        }
        
        
        Slog.w(TAG, "Returning empty DEX array as last resort");
        return new long[]{};
    }
    
    
    private static byte[] createMinimalDexBytes() {
        
        
        
        
        
        byte[] dexHeader = {
            'd', 'e', 'x', '\n',  
            0x30, 0x33, 0x35, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x70, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00   
        };
        
        return dexHeader;
    }
}
