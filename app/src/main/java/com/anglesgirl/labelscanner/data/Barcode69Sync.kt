package com.anglesgirl.labelscanner.data

import android.content.Context
import android.util.Log

/**
 * 物料编码 ⟷ 69 码 的双向同步（本地 SQLite ↔ 云端 Turso）。
 *
 * 设计依据（用户明确要求）：
 *  - **每次打开先拉云端，再与本地比对**；
 *  - **云端没有的传上去**（本地新增）；
 *  - **云端有而本地没有的拉下来**；
 *  - **互相冲突的不自动改，标明后由用户手动修正为正确的**。
 *
 * 为什么冲突不自动裁决：两边的时间字段语义不同（本地 updated_at / 云端 created_at），
 * 按时间"谁新用谁"会把"云端更早创建但刚被改过"这类情况判反 —— 而这是**主数据**，
 * 判错会污染后续所有采集。所以此处**只标记、不猜测**，把决定权交回用户。
 *
 * 注意：这是唯一一张需要双向同步的表。采集记录（records）不在此范围。
 */
object Barcode69Sync {

    private const val TAG = "Barcode69Sync"

    /** 一条冲突：同一个 69 码，两边物料编码不同。 */
    data class Conflict(
        val ean: String,
        val localMaterial: String,
        val remoteMaterial: String,
    )

    data class SyncResult(
        /** 从云端拉下来的条数 */
        val pulled: Int = 0,
        /** 推到云端的条数 */
        val pushed: Int = 0,
        /** 两边一致、无需处理的条数 */
        val same: Int = 0,
        /** 冲突（需人工修正），已原样保留本地值、不做覆盖 */
        val conflicts: List<Conflict> = emptyList(),
        /** 未配置 / 网络失败等；非 null 表示这次同步没跑成 */
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null

        /** 给界面用的一句话摘要。 */
        fun summary(): String = when {
            error != null -> error
            conflicts.isEmpty() -> "同步完成：拉取 $pulled，上传 $pushed，一致 $same"
            else -> "同步完成：拉取 $pulled，上传 $pushed，一致 $same；**${conflicts.size} 条冲突待确认**"
        }
    }

    /**
     * 执行一次同步。**必须在后台线程调用**（内部有网络与数据库操作）。
     * 调用方可直接用 [syncAsync]。
     */
    fun sync(context: Context): SyncResult {
        val appContext = context.applicationContext
        val baseUrl = com.anglesgirl.labelscanner.SettingsActivity.getUrl(appContext)
        val token = com.anglesgirl.labelscanner.SettingsActivity.getToken(appContext)

        if (baseUrl.isBlank() || token.isBlank()) {
            return SyncResult(error = "未配置云端地址或 Token，已跳过同步")
        }

        // ① 拉云端全表
        val remote: Map<String, String> = try {
            Turso69Client.listAll(baseUrl, token)
                .mapNotNull { (ean, material, _) ->
                    val e = ean.trim(); val m = material.trim()
                    if (e.isNotEmpty() && m.isNotEmpty()) e to m else null
                }
                .toMap()
        } catch (t: Throwable) {
            Log.w(TAG, "拉取云端失败", t)
            return SyncResult(error = "拉取云端失败：${t.message ?: t.javaClass.simpleName}")
        }

        // ② 读本地全表
        val local = try {
            Barcode69Lookup(appContext).allLocal()
        } catch (t: Throwable) {
            Log.w(TAG, "读本地失败", t)
            return SyncResult(error = "读取本地数据失败：${t.message ?: t.javaClass.simpleName}")
        }

        // ③ 比对
        val toPull = LinkedHashMap<String, String>()
        val toPush = LinkedHashMap<String, String>()
        val conflicts = mutableListOf<Conflict>()
        var same = 0

        for ((ean, remoteMat) in remote) {
            val localMat = local[ean]
            when {
                localMat == null -> toPull[ean] = remoteMat          // 云端有、本地无 → 拉下来
                localMat == remoteMat -> same++                       // 一致
                else -> conflicts += Conflict(ean, localMat, remoteMat) // 冲突 → 只标记
            }
        }
        for ((ean, localMat) in local) {
            if (!remote.containsKey(ean)) toPush[ean] = localMat      // 本地有、云端无 → 传上去
        }

        // ④ 落库：拉下来的只补空位，不覆盖本地已有值
        val pulled = try {
            Barcode69Lookup(appContext).putIfAbsent(toPull)
        } catch (t: Throwable) {
            Log.w(TAG, "写入本地失败", t)
            0
        }

        // ⑤ 上传本地新增（逐条，单条失败不影响其余）
        var pushed = 0
        for ((ean, mat) in toPush) {
            try {
                if (Turso69Client.upsert(baseUrl, token, ean, mat, null) == null) pushed++   // null = 成功
            } catch (t: Throwable) {
                Log.w(TAG, "上传 $ean 失败", t)
            }
        }

        Log.i(TAG, "同步完成 pulled=$pulled pushed=$pushed same=$same conflicts=${conflicts.size}")
        return SyncResult(pulled, pushed, same, conflicts)
    }

    /** 后台线程执行，结果回主线程回调。 */
    fun syncAsync(context: Context, onDone: (SyncResult) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val result = try {
                sync(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "同步异常", t)
                SyncResult(error = "同步异常：${t.message ?: t.javaClass.simpleName}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(result) }
        }.start()
    }

    /**
     * 人工裁决一条冲突。
     * @param useRemote true = 采用云端值（覆盖本地并保持一致）；false = 采用本地值（推到云端）
     */
    fun resolve(
        context: Context,
        conflict: Conflict,
        useRemote: Boolean,
    ): Boolean = try {
        val appContext = context.applicationContext
        if (useRemote) {
            Barcode69Lookup(appContext).learn(conflict.ean, conflict.remoteMaterial)
            true
        } else {
            val baseUrl = com.anglesgirl.labelscanner.SettingsActivity.getUrl(appContext)
            val token = com.anglesgirl.labelscanner.SettingsActivity.getToken(appContext)
            if (baseUrl.isBlank() || token.isBlank()) false
            else {
                Barcode69Lookup(appContext).learn(conflict.ean, conflict.localMaterial)
                Turso69Client.upsert(baseUrl, token, conflict.ean, conflict.localMaterial, null) == null
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "裁决冲突失败", t)
        false
    }

    /** 人工输入正确的物料编码：本地与云端都改成它。 */
    fun resolveWith(context: Context, ean: String, material: String): Boolean = try {
        val appContext = context.applicationContext
        Barcode69Lookup(appContext).learn(ean, material)
        val baseUrl = com.anglesgirl.labelscanner.SettingsActivity.getUrl(appContext)
        val token = com.anglesgirl.labelscanner.SettingsActivity.getToken(appContext)
        if (baseUrl.isBlank() || token.isBlank()) true   // 本地已改；云端下次同步再推
        else Turso69Client.upsert(baseUrl, token, ean, material, null) == null   // null = 成功
    } catch (t: Throwable) {
        Log.w(TAG, "人工修正失败", t)
        false
    }
}
