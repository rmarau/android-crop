package com.soundcloud.android.crop;

class Log {

    private static final String TAG = "android-crop";

    public static void d(String msg) {
        android.util.Log.d(TAG, msg);
    }

    public static void d(String tag, String msg) {
        android.util.Log.d(tag, msg);
    }

    public static void e(String tag, String msg) {
        android.util.Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable e) {
        android.util.Log.e(tag, msg, e);
    }

    public static void e(String msg, Throwable e) {
        android.util.Log.e(TAG, msg, e);
    }

}
