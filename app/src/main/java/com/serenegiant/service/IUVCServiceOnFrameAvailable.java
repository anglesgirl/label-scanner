package com.serenegiant.service;

/**
 * Stub for serenegiant service 包的回调接口。
 * RendererHolder 仅将其实例存于 SparseArray 并调用 onFrameAvailable()，
 * 本项目未启用 UVCService 架构，补最小同签名接口即可编译运行。
 */
public interface IUVCServiceOnFrameAvailable {
    void onFrameAvailable() throws android.os.RemoteException;
}
