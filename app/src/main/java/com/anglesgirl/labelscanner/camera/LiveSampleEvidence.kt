package com.anglesgirl.labelscanner.camera

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable sidecar survives activity recreation. The final still must not overwrite four-frame evidence. */
internal object LiveSampleEvidence {
    private const val KEY = "label_verified_sample"
    fun isVerified(uri: Uri) = uri.getQueryParameter(KEY) != null

    fun attach(context: Context, photo: Uri, result: BurstConsensus.Result): Uri {
        val json = JSONObject().put("barcodes", JSONArray(result.barcodes))
            .put("ocrText", result.ocrText)
            .put("unconfirmedBarcodes", JSONArray(result.unconfirmedBarcodes))
            .put("unconfirmedOcrLines", JSONArray(result.unconfirmedOcrLines))
            .put("frameCount", result.frameCount)
        val file = File(context.cacheDir, "captures/verified_${System.nanoTime()}.json")
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
        val evidence = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return photo.buildUpon().appendQueryParameter(KEY, evidence.toString()).build()
    }

    fun read(resolver: ContentResolver, photo: Uri): BurstConsensus.Result {
        val uri = Uri.parse(requireNotNull(photo.getQueryParameter(KEY)))
        require(uri.scheme == "content" && uri.authority == photo.authority)
        val text = requireNotNull(resolver.openInputStream(uri)).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        fun list(key: String): List<String> {
            val array = json.getJSONArray(key)
            return (0 until array.length()).map { array.getString(it) }
        }
        return BurstConsensus.Result(list("barcodes"), json.getString("ocrText"),
            list("unconfirmedBarcodes"), list("unconfirmedOcrLines"), json.getInt("frameCount"))
    }
}
