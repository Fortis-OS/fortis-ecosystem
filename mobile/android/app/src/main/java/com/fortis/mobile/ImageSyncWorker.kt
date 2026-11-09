package com.fortis.mobile

import android.content.Context
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fortis.mobile.network.AgentClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ImageSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences("FortisPrefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("ip", "") ?: ""
        val apiKey = sharedPrefs.getString("apiKey", "") ?: ""

        if (ip.isEmpty() || apiKey.isEmpty()) {
            return@withContext Result.failure()
        }

        val lastSyncId = sharedPrefs.getLong("lastSyncImageId", -1)
        var maxIdSynced = lastSyncId

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA
        )

        val sortOrder = "${MediaStore.Images.Media._ID} ASC"
        val selection = "${MediaStore.Images.Media._ID} > ?"
        val selectionArgs = arrayOf(lastSyncId.toString())

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        cursor?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

            val client = AgentClient.getClient(apiKey)

            while (c.moveToNext()) {
                val id = c.getLong(idColumn)
                val data = c.getString(dataColumn)

                val file = File(data)
                if (file.exists()) {
                    try {
                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart(
                                "file", 
                                file.name,
                                file.asRequestBody("image/*".toMediaType())
                            )
                            .build()

                        val request = Request.Builder()
                            .url("http://$ip:8023/api/v1/files/drop")
                            .post(requestBody)
                            .build()

                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            maxIdSynced = id
                        }
                        response.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Stop trying on network failure to avoid skipping files
                        break
                    }
                }
            }
        }

        sharedPrefs.edit().putLong("lastSyncImageId", maxIdSynced).apply()
        Result.success()
    }
}
