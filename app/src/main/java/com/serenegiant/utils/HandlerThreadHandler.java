package com.serenegiant.utils;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * Stub for com.serenegiant:common 的 HandlerThreadHandler。
 * 见 FpsCounter.java 的说明。USBMonitor 运行期仅调用
 * createHandler(String) 静态工厂，其余行为继承自 Handler。
 */
public class HandlerThreadHandler extends Handler {
    private HandlerThreadHandler(final Looper looper) {
        super(looper);
    }

    public static HandlerThreadHandler createHandler(final String name) {
        final HandlerThread thread = new HandlerThread(name);
        thread.start();
        return new HandlerThreadHandler(thread.getLooper());
    }
}
