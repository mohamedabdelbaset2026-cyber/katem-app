package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class HostingerUploadResult(
    val mediaId: String,
    val remotePath: String,
    val downloadUrl: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long
)

data class HostingerHealthStatus(
    val reachable: Boolean,
    val message: String
)

/**
 * Zero-credential Hostinger Shared Hosting HTTPS Media Gateway client.
 *
 * Security Architecture:
 * - Zero Hostinger FTP/SSH/cPanel credentials exist inside the Android APK.
 * - Every upload/download request attaches the authenticated user's live Firebase ID Token
 *   via `Authorization: Bearer <idToken>`.
 * - The Hostinger PHP gateway (`/hostinger-backend/upload.php` & `download.php`) verifies
 *   the Firebase JWT and conversation membership before storing/streaming files inside a
 *   private directory outside `public_html`.
 */
class HostingerMediaClient(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isConfigured(baseUrl: String): Boolean {
        val trimmed = baseUrl.trim()
        return trimmed.startsWith("https://") && !trimmed.contains("your-hostinger-domain.com")
    }

    suspend fun checkGatewayHealth(baseUrl: String): HostingerHealthStatus = withContext(Dispatchers.IO) {
        if (!isConfigured(baseUrl)) {
            return@withContext HostingerHealthStatus(
                reachable = false,
                message = "يرجى إدخال رابط بوابة PHP على نطاق Hostinger الخاص بك (https://...)."
            )
        }
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/health.php")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    HostingerHealthStatus(true, "بوابة Hostinger متصلة وتعمل بنجاح (HTTP ${response.code}).")
                } else {
                    HostingerHealthStatus(false, "الخادم استجاب برمز HTTP ${response.code}. تأكد من رفع ملفات hostinger-backend.")
                }
            }
        }.getOrElse { err ->
            HostingerHealthStatus(false, "تعذر الاتصال بالخادم: ${err.localizedMessage ?: "خطأ في الشبكة"}")
        }
    }

    suspend fun uploadUriToHostinger(
        baseUrl: String,
        conversationId: String,
        uri: Uri,
        fallbackMimeType: String = "application/octet-stream"
    ): Result<HostingerUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(isConfigured(baseUrl)) {
                "لم يتم ربط رابط خادم Hostinger بعد. انتقل إلى الإعدادات ← تخزين Hostinger لإدخال رابط بوابة PHP الخاصة بك."
            }
            val idToken = Firebase.auth.currentUser?.getIdToken(false)?.await()?.token
                ?: error("يجب تسجيل الدخول أولًا للمصادقة مع خادم Hostinger.")

            val mimeType = context.contentResolver.getType(uri) ?: fallbackMimeType
            val (fileName, reportedSize) = queryUriMetadata(uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("تعذر قراءة الملف المختار.")
            require(bytes.size <= MAX_UPLOAD_BYTES) {
                "حجم الملف يتجاوز الحد الأقصى المسموح (25 ميجابايت)."
            }

            performMultipartUpload(
                baseUrl = baseUrl,
                idToken = idToken,
                conversationId = conversationId,
                fileName = fileName,
                mimeType = mimeType,
                fileBytes = bytes,
                sizeBytes = if (reportedSize > 0) reportedSize else bytes.size.toLong()
            )
        }
    }

    suspend fun uploadLocalFileToHostinger(
        baseUrl: String,
        conversationId: String,
        file: File,
        mimeType: String
    ): Result<HostingerUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(isConfigured(baseUrl)) {
                "لم يتم ربط رابط خادم Hostinger بعد. انتقل إلى الإعدادات ← تخزين Hostinger لإدخال رابط بوابة PHP الخاصة بك."
            }
            val idToken = Firebase.auth.currentUser?.getIdToken(false)?.await()?.token
                ?: error("يجب تسجيل الدخول أولًا للمصادقة مع خادم Hostinger.")
            require(file.exists() && file.length() > 0L) { "الملف الصوتي غير موجود أو فارغ." }
            require(file.length() <= MAX_UPLOAD_BYTES) { "حجم الملف يتجاوز الحد الأقصى المسموح (25 ميجابايت)." }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("conversationId", conversationId)
                .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaTypeOrNull()))
                .build()

            executeUploadRequest(
                baseUrl = baseUrl,
                idToken = idToken,
                requestBody = requestBody,
                fallbackMime = mimeType,
                fallbackName = file.name,
                fallbackSize = file.length()
            )
        }
    }

    suspend fun downloadAuthorizedToCache(downloadUrl: String, fileName: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val idToken = Firebase.auth.currentUser?.getIdToken(false)?.await()?.token
                    ?: error("الجلسة غير مصادق عليها.")
                val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").ifBlank { "media_file" }
                val cacheDir = File(context.cacheDir, "katem_media").apply { mkdirs() }
                val targetFile = File(cacheDir, "${downloadUrl.hashCode()}_$safeName")
                if (targetFile.exists() && targetFile.length() > 0L) {
                    return@runCatching targetFile
                }

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("Authorization", "Bearer $idToken")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("فشل تنزيل الملف من Hostinger (HTTP ${response.code}).")
                    }
                    val bodyStream = response.body?.byteStream() ?: error("استجابة فارغة من الخادم.")
                    targetFile.outputStream().use { out -> bodyStream.copyTo(out) }
                }
                targetFile
            }
        }

    private fun performMultipartUpload(
        baseUrl: String,
        idToken: String,
        conversationId: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        sizeBytes: Long
    ): HostingerUploadResult {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("conversationId", conversationId)
            .addFormDataPart("file", fileName, fileBytes.toRequestBody(mimeType.toMediaTypeOrNull()))
            .build()

        return executeUploadRequest(
            baseUrl = baseUrl,
            idToken = idToken,
            requestBody = requestBody,
            fallbackMime = mimeType,
            fallbackName = fileName,
            fallbackSize = sizeBytes
        )
    }

    private fun executeUploadRequest(
        baseUrl: String,
        idToken: String,
        requestBody: MultipartBody,
        fallbackMime: String,
        fallbackName: String,
        fallbackSize: Long
    ): HostingerUploadResult {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/upload.php")
            .header("Authorization", "Bearer $idToken")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val serverError = runCatching { JSONObject(rawBody).optString("error") }.getOrDefault("")
                error(serverError.ifBlank { "فشل رفع الملف إلى Hostinger (HTTP ${response.code})." })
            }
            val json = JSONObject(rawBody)
            return HostingerUploadResult(
                mediaId = json.getString("mediaId"),
                remotePath = json.getString("remotePath"),
                downloadUrl = json.getString("downloadUrl"),
                mimeType = json.optString("mimeType", fallbackMime).ifBlank { fallbackMime },
                fileName = json.optString("fileName", fallbackName).ifBlank { fallbackName },
                sizeBytes = json.optLong("sizeBytes", fallbackSize)
            )
        }
    }

    private fun queryUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "attachment_${System.currentTimeMillis()}"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) {
                    cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
                if (sizeIdx >= 0) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    companion object {
        private const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024 // 25 MB
    }
}
