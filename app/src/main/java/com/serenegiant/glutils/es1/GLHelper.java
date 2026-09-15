package com.serenegiant.glutils.es1;

/*
 * UVCCamera
 * library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2015 saki t_saki@serenegiant.com
 *
 * File name: GLHelper.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
 * Files in the jni/libjpeg, jni/libusb, jin/libuvc, jni/rapidjson folder may have a different license, see the respective files.
*/

import android.opengl.GLES20;
import android.util.Log;

/**
 * GL ES1 时代的工具类（common 4.1.1 提供）。
 *
 * AndroidUSBCamera 2.3.4 的 UVCCameraTextureView$RenderThread.release()
 * 只引用 `GLHelper.deleteTex(int)` 这一个静态方法（渲染线程退出时删纹理）。
 * 该库的 glutils 依赖被我们替换成项目内实现（com.serenegiant:common:4.1.1
 * 在公共仓库不存在），这里补上同签名实现，避免 NoClassDefFoundError。
 *
 * 全量字节码扫描确认：这是 aar 中唯一缺失的 glutils 类。
 */
public class GLHelper {
	private static final boolean DEBUG = false;
	private static final String TAG = "GLHelper";

	private GLHelper() {
	}

	/** 删除指定纹理（与 common 4.1.1 签名一致：static deleteTex(I)V） */
	public static void deleteTex(final int hTex) {
		if (DEBUG) Log.v(TAG, "deleteTex:");
		final int[] tex = new int[] { hTex };
		GLES20.glDeleteTextures(1, tex, 0);
	}
}
