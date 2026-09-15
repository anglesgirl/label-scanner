package com.serenegiant.utils;

import android.os.Build;

/**
 * Stub for com.serenegiant:common 的 BuildCheck（SDK 版本判断）。
 * 见 FpsCounter.java 的说明。USBMonitor 运行期仅调用
 * isAndroid5 / isLollipop / isMarshmallow 三个静态方法。
 */
public class BuildCheck {
    public static boolean isAndroid5() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public static boolean isLollipop() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public static boolean isMarshmallow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }
}
