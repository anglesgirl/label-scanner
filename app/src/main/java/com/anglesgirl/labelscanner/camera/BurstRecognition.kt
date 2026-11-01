package com.anglesgirl.labelscanner.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A burst is carried with its full-resolution photo URI, so activity recreation cannot lose votes. */
internal object BurstRecognition {
    private const val FRAME_QUERY = "label_burst_frames"
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "label-burst-recognition").apply { isDaemon = true }
    }

    fun attach(photo: Uri, frames: List<Uri>): Uri = photo.buildUpon()
        .appendQueryParameter(FRAME_QUERY, JSONArray(frames.map(Uri::toString)).toString()).build()

    fun isBurst(uri: Uri): Boolean = uri.getQueryParameter(FRAME_QUERY) != null

    fun recognize(
        resolver: ContentResolver,
        uri: Uri,
        onResult: (BurstConsensus.Result) -> Unit,
        onError: (String) -> Unit,
    ) {
        worker.execute {
            val frames = runCatching {
                val array = JSONArray(uri.getQueryParameter(FRAME_QUERY) ?: "[]")
                (0 until minOf(array.length(), BurstFrameCollector.TARGET_FRAMES)).map { i ->
                    Uri.parse(array.getString(i)).also { frame ->
                        require(frame.scheme == "content" && frame.authority == uri.authority)
                    }
                }.distinct()
            }.getOrElse {
                onError("连续帧数据无法读取，请重新拍摄")
                return@execute
            }
            val photo = uri.buildUpon().clearQuery().build()
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val observations = mutableListOf<BurstConsensus.Frame>()
            var readableFrames = 0
            try {
                // Never clone a photograph to manufacture votes. Each URI is one physical frame;
                // the high-resolution still supplies extra detail, but is also only ONE vote.
                for ((index, frameUri) in (frames + photo).withIndex()) {
                    var raw: Bitmap? = null
                    var upright: Bitmap? = null
                    var deferRecycle = false
                    try {
                        val decoded = StaticRecognizer.decodeSampledBitmap(resolver, frameUri)
                            ?: throw IllegalStateException("无法读取第 ${index + 1} 帧")
                        raw = decoded
                        val bitmap = StaticRecognizer.applyExifRotation(resolver, frameUri, decoded)
                        upright = bitmap
                        readableFrames++
                        val codes = if (frameUri == photo) ZxingDecoder.decodeUnion(bitmap)
                            else ZxingDecoder.decode(bitmap, scale = 1f)
                        val task = client.process(InputImage.fromBitmap(bitmap, 0))
                        val text = try {
                            Tasks.await(task, 10, TimeUnit.SECONDS).text.orEmpty()
                        } catch (e: Exception) {
                            // A timed-out ML Kit task still owns the bitmap. Recycle on completion,
                            // never underneath the native recognizer.
                            if (!task.isComplete) {
                                deferRecycle = true
                                task.addOnCompleteListener(ExecutorDirect) {
                                    if (bitmap !== decoded) bitmap.recycle()
                                    decoded.recycle()
                                }
                            }
                            Log.w("BurstRecognition", "OCR frame ${index + 1} failed", e)
                            ""
                        }
                        Log.i("BurstRecognition", "frame=${index + 1} codes=$codes ocr=$text")
                        observations += BurstConsensus.Frame(codes, text)
                    } catch (e: Exception) {
                        Log.w("BurstRecognition", "Frame ${index + 1} failed", e)
                        observations += BurstConsensus.Frame(emptyList(), "")
                    } finally {
                        if (!deferRecycle) {
                            if (upright !== raw) upright?.recycle()
                            raw?.recycle()
                        }
                    }
                }
                if (readableFrames == 0) onError("连续帧与照片均无法读取，请重新拍摄")
                else onResult(BurstConsensus.resolve(observations))
            } catch (e: Exception) {
                Log.e("BurstRecognition", "Burst recognition failed", e)
                onError("多帧识别失败，请重新拍摄：${e.message.orEmpty()}")
            } finally {
                client.close()
                // Temporary preview JPEGs are no longer needed after their evidence is preserved.
                frames.forEach { frame -> runCatching { resolver.delete(frame, null, null) } }
            }
        }
    }

    private val ExecutorDirect = java.util.concurrent.Executor { it.run() }
}
