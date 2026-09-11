package com.anglesgirl.labelscanner.util

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程诊断日志：把实际采集过程上报，用于离线分析现场问题。
 *
 * 为什么需要：相机手感（变焦快慢、稳定判定、拍照时机）和识别漏检
 * **只能在真实使用中暴露**，靠用户口述很难定位。有了日志就能直接看到
 * 现场每一帧的识别结果与参数，据此调参而不是猜。
 *
 * 实现要点：
 *  - 用 HttpURLConnection，**不引入 OkHttp**（避免与其他库的传递依赖/R8 冲突）。
 *  - 单线程队列 + 失败静默：诊断绝不能影响采集流程，更不能拖慢相机。
 *  - 高频事件（每帧）由调用方采样后上报，本类不做节流判断。
 *  - 可在设置里关闭。
 */
object Diag {

    private const val TAG = "Diag"
    private const val ENDPOINT = "https://log.anglesgirl.eu.org/v1/events"
    private const val APP = "label-scanner"

    /** 队列上限：超过就丢弃，避免离线时无限堆积占内存。 */
    private const val QUEUE_MAX = 200

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-uploader").apply { isDaemon = true }
    }

    private val queue = ArrayDeque<String>()
    private val uploading = AtomicBoolean(false)

    /** 设置页可关。默认开启（用户明确要求记录使用过程）。 */
    @Volatile
    var enabled: Boolean = true

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    /** 设备指纹：多台手机上报时用于区分（同事手机型号杂）。 */
    private val deviceTag: String by lazy {
        "${Build.MANUFACTURER}-${Build.MODEL}"
    }

    /**
     * 上报一个事件。
     * @param name 事件名（如 box_parse / photo_taken）
     * @param fields 字段（值会被 JSON 化；请勿传敏感信息）
     */
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        try {
            val obj = JSONObject()
            obj.put("app", APP)
            obj.put("event", name)
            obj.put("device", deviceTag)
            obj.put("timestamp", iso.format(Date()))
            val f = JSONObject()
            for ((k, v) in fields) {
                f.put(k, v?.toString() ?: "-")
            }
            obj.put("fields", f)
            enqueue(obj.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "构造事件失败: $name", t)
        }
    }

    @Synchronized
    private fun enqueue(body: String) {
        if (queue.size >= QUEUE_MAX) queue.removeFirst()
        queue.addLast(body)
        if (uploading.compareAndSet(false, true)) {
            executor.execute { drain() }
        }
    }

    private fun drain() {
        try {
            while (true) {
                val body = synchronized(this) { queue.removeFirstOrNull() } ?: break
                post(body)
            }
        } finally {
            uploading.set(false)
            // 运行期间可能又有新事件进来，补一次
            val more = synchronized(this) { queue.isNotEmpty() }
            if (more && uploading.compareAndSet(false, true)) {
                executor.execute { drain() }
            }
        }
    }

    private fun post(body: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val out: OutputStream = conn.outputStream
            out.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) Log.w(TAG, "上报返回 $code")
        } catch (t: Throwable) {
            // 网络不通/服务未起都不影响采集
            Log.w(TAG, "上报失败（忽略）", t)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
