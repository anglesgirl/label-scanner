package com.anglesgirl.labelscanner.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 数据库后台执行器：所有 SQLite / MediaStore 操作挪到单线程后台，
 * 结果（含异常）回主线程，避免数据量增大后主线程卡顿。
 *
 * 与相机 zxing 线程池同风格（Executors + Handler），零新依赖；
 * 单线程串行保证写操作顺序，读操作也走同一队列避免读写竞态。
 */
object DbExecutor {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "db-worker").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** 后台执行 block，结果（成功值或异常）回到主线程回调。 */
    fun <T> run(block: () -> T, onMain: (Result<T>) -> Unit) {
        executor.execute {
            val result = try {
                Result.success(block())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            main.post { onMain(result) }
        }
    }
}
