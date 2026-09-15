package com.serenegiant.utils;

/**
 * Stub for com.serenegiant:common 的 FpsCounter（帧率计数器）。
 *
 * AndroidUSBCamera 2.3.4 的 pom 声明依赖 com.serenegiant:common:4.1.1，
 * 但该坐标在 Maven Central / JitPack / Google Maven 均不存在（仓库已下线）。
 * aar 内 UVCCameraTextureView/RenderThread 运行期仅调用
 * 无参构造 / reset() / update() / count()，补一个同签名的最小实现。
 */
public class FpsCounter {
    private long mStartTime;
    private long mCount;
    private float mFps;

    public FpsCounter() {
        mStartTime = System.currentTimeMillis();
    }

    public void count() {
        mCount++;
    }

    public FpsCounter update() {
        final long now = System.currentTimeMillis();
        final long elapsed = now - mStartTime;
        if (elapsed >= 1000) {
            mFps = mCount * 1000.0f / elapsed;
            mCount = 0;
            mStartTime = now;
        }
        return this;
    }

    public FpsCounter reset() {
        mStartTime = System.currentTimeMillis();
        mCount = 0;
        mFps = 0.0f;
        return this;
    }

    public float getFps() {
        return mFps;
    }
}
