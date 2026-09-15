package com.serenegiant.widget;

/**
 * Stub for com.serenegiant:common 的 IAspectRatioView。
 *
 * AndroidUSBCamera 2.3.4 的 pom 声明依赖 com.serenegiant:common:4.1.1，
 * 但该坐标在 Maven Central / JitPack / Google Maven 均不存在（仓库已下线）。
 * 而 aar 内的 AspectRatioTextureView / UVCCameraTextureView 仅在签名上
 * implements 此接口，运行时无人调用其方法 —— 补一个最小同签名接口即可编译。
 */
public interface IAspectRatioView {
    void setAspectRatio(double aspectRatio);

    void setAspectRatio(int width, int height);

    double getAspectRatio();
}
