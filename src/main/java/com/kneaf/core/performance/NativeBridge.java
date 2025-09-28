package com.kneaf.core.performance;

/**
 * Minimal JNI bridge for worker-based native processing.
 * Native library must provide the corresponding functions.
 */
public final class NativeBridge {
    static {
        try {
            System.loadLibrary("kneaf");
        } catch (UnsatisfiedLinkError e) {
            // library may not be present in test environment
        }
    }

    private NativeBridge() {}

    public static native long nativeCreateWorker(int concurrency);
    public static native void nativePushTask(long workerHandle, byte[] payload);
    public static native byte[] nativePollResult(long workerHandle);
    public static native void nativeDestroyWorker(long workerHandle);
}
