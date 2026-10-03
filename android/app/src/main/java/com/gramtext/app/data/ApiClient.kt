package com.gramtext.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val success: Boolean,
    val text: String,
    val language: String,
    val message: String,
    val scanId: Int?,
    val engine: String?,
    val confidence: Double?,
)

/** kind: "network" | "timeout" | "server" (message comes from the backend, already friendly). */
class ApiException(val kind: String, message: String) : Exception(message)

class ApiClient(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun request(path: String) = Request.Builder()
        .url(prefs.serverUrl.trimEnd('/') + path)
        .header("X-Client-Id", prefs.clientId)

    suspend fun health(baseUrl: String = prefs.serverUrl): Boolean = try {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/health").build()
        http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().newCall(req).await().use { it.isSuccessful }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun ocr(jpeg: ByteArray): OcrResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val json = call(request("/api/ocr").post(body).build()) { JSONObject(it.body!!.string()) }
        return OcrResult(
            success = json.optBoolean("success"),
            text = json.optString("text"),
            language = json.optString("language", "und"),
            message = json.optString("message"),
            scanId = if (json.isNull("scan_id")) null else json.optInt("scan_id"),
            engine = json.optString("engine").ifEmpty { null },
            confidence = if (json.isNull("confidence")) null else json.optDouble("confidence"),
        )
    }

    /**
     * Fast path: OCR + speech in one streamed request. Emits one JSON event per line:
     * type = "text" | "audio" (base64 MP3 sentence) | "error" | "done". See backend reader.py.
     */
    fun read(jpeg: ByteArray): Flow<JSONObject> = flow {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val response = try {
            http.newCall(request("/api/read").post(body).build()).await()
        } catch (e: InterruptedIOException) {
            throw ApiException("timeout", "timeout")
        } catch (e: IOException) {
            throw ApiException("network", "network")
        }
        response.use {
            if (!it.isSuccessful) {
                val detail = runCatching { JSONObject(it.body!!.string()).optString("detail") }.getOrNull()
                throw ApiException("server", detail?.ifBlank { null } ?: "Server error (${it.code})")
            }
            val source = it.body!!.source()
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isNotBlank()) emit(JSONObject(line))
                }
            } catch (e: InterruptedIOException) {
                throw ApiException("timeout", "timeout")
            } catch (e: IOException) {
                throw ApiException("network", "network")
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun tts(text: String): ByteArray {
        val body = JSONObject().put("text", text).toString()
            .toRequestBody("application/json".toMediaType())
        return call(request("/api/tts").post(body).build()) { it.body!!.bytes() }
    }

    suspend fun feedback(scanId: Int?, helpful: Boolean, correctedText: String?) {
        val json = JSONObject().put("helpful", helpful)
        if (scanId != null) json.put("scan_id", scanId)
        if (!correctedText.isNullOrBlank()) json.put("corrected_text", correctedText)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        call(request("/api/feedback").post(body).build()) { }
    }

    private suspend fun <T> call(req: Request, read: (Response) -> T): T {
        val response = try {
            http.newCall(req).await()
        } catch (e: InterruptedIOException) {
            throw ApiException("timeout", "timeout")
        } catch (e: IOException) {
            throw ApiException("network", "network")
        }
        return response.use {
            if (!it.isSuccessful) {
                val detail = runCatching { JSONObject(it.body!!.string()).optString("detail") }.getOrNull()
                throw ApiException("server", detail?.ifBlank { null } ?: "Server error (${it.code})")
            }
            read(it)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
