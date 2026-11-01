package com.anglesgirl.labelscanner.camera

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sidecar handoff for codes confirmed during a live top-to-bottom page scan. */
internal object LiveCodePageEvidence {
    private const val KEY = "label_live_code_page"

    fun attach(context: Context, codes: List<String>): Uri {
        require(codes.isNotEmpty())
        val file = File(context.cacheDir, "captures/live_codes_${System.nanoTime()}.json")
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("codes", JSONArray(codes)).toString())
        val evidence = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return evidence.buildUpon().appendQueryParameter(KEY, evidence.toString()).build()
    }

    fun isPage(uri: Uri): Boolean = uri.getQueryParameter(KEY) != null

    fun read(resolver: ContentResolver, uri: Uri): List<String> {
        val evidence = Uri.parse(requireNotNull(uri.getQueryParameter(KEY)))
        require(evidence.scheme == "content" && evidence.authority == uri.authority)
        val text = requireNotNull(resolver.openInputStream(evidence)).bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("codes")
        return (0 until array.length()).map { array.getString(it) }
            .map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
