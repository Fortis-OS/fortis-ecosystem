package com.fortis.mobile

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.network.FortisApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.util.concurrent.TimeUnit

/**
 * Daily camera-roll upload to the laptop's camera folder (docs/security §6.4):
 * off by default, unmetered network only, reads MediaStore content URIs,
 * remembers the last uploaded MediaStore id.
 */
class ImageSyncWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val laptop = DeviceRepository.get(context).defaultLaptop() ?: return@withContext Result.success()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var lastId = prefs.getLong(KEY_LAST_ID, -1)

        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE)
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Images.Media._ID} > ?", arrayOf(lastId.toString()),
            "${MediaStore.Images.Media._ID} ASC",
        ) ?: return@withContext Result.retry()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val name = c.getString(nameCol) ?: "photo_$id.jpg"
                val mime = (c.getString(mimeCol) ?: "image/jpeg").toMediaTypeOrNull()
                val part = object : RequestBody() {
                    override fun contentType() = mime
                    override fun writeTo(sink: BufferedSink) {
                        context.contentResolver.openInputStream(uri)?.use { sink.writeAll(it.source()) }
                    }
                }
                val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, part).build()
                val ok = try {
                    FortisApiClient.getClient()
                        .newCall(FortisApiClient.buildRequest("/api/v1/files/drop?dest=camera", laptop) { post(body) })
                        .execute().use { it.isSuccessful }
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    prefs.edit().putLong(KEY_LAST_ID, lastId).apply()
                    return@withContext Result.retry() // don't skip files on failure
                }
                lastId = id
            }
        }
        prefs.edit().putLong(KEY_LAST_ID, lastId).apply()
        Result.success()
    }

    companion object {
        private const val WORK = "CameraSync"
        private const val PREFS = "fortis_camera_sync"
        private const val KEY_LAST_ID = "last_id"
        private const val KEY_ON = "enabled"

        fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

        /** Returns the new state. When turned on, only photos taken from now on are uploaded. */
        fun setEnabled(context: Context, on: Boolean): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wm = WorkManager.getInstance(context)
            if (on) {
                if (prefs.getLong(KEY_LAST_ID, -1) < 0) prefs.edit().putLong(KEY_LAST_ID, newestImageId(context)).apply()
                val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
                val req = PeriodicWorkRequestBuilder<ImageSyncWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
                wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
            } else {
                wm.cancelUniqueWork(WORK)
            }
            prefs.edit().putBoolean(KEY_ON, on).apply()
            return on
        }

        private fun newestImageId(context: Context): Long =
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
                "${MediaStore.Images.Media._ID} DESC",
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }
}
