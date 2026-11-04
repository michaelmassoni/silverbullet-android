package io.github.michaelmassoni.silverbullet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Flushes the capture queue to the server whenever there is a network. WorkManager persists the
 * job across app restarts and reboots, so a capture made in a dead zone is sent when you are back
 * on the tailnet, even if the app is never opened again.
 */
class CaptureWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = flushLock.withLock {
        val prefs = Prefs(applicationContext)
        val queue = CaptureQueue(applicationContext)
        val entries = queue.entries()
        if (entries.isEmpty()) return@withLock Result.success()

        // CookieManager is safest touched from the main thread.
        val server = prefs.server ?: return@withLock Result.success() // not set up yet
        val client = withContext(Dispatchers.Main) { SbClient.forServer(server, prefs.token) }

        try {
            for ((page, group) in entries.groupBy { it.page }) {
                val path = "$page.md"
                val day = group.first().day
                val addition = group.joinToString("\n") { it.markdown }
                // Once a PUT starts, finish it and delete the queue files together, so a
                // cancelled/replaced worker can never send the same bullets twice.
                withContext(NonCancellable + Dispatchers.IO) {
                    appendToPage(client, path, day, addition)
                    group.forEach { it.file.delete() }
                }
            }
            cancelNotification(applicationContext)
            Result.success()
        } catch (e: AuthRequiredException) {
            notifySignIn(applicationContext, queue.count())
            Result.success() // MainActivity re-kicks the queue after you sign in
        } catch (e: IOException) {
            Log.w(TAG, "capture flush failed, will retry", e)
            Result.retry()
        }
    }

    private fun appendToPage(client: SbClient, path: String, day: String?, addition: String) {
        repeat(6) {
            val current = client.read(path)
            val newBody = if (current.body == null) {
                if (day != null) "---\ntags: journal\ndate: $day\n---\n$addition\n" else "$addition\n"
            } else {
                val b = current.body
                (if (b.isEmpty() || b.endsWith("\n")) b else "$b\n") + addition + "\n"
            }
            if (client.write(path, newBody, current.etag, mustNotExist = current.body == null)) return
            // 412: the page changed between our read and write (e.g. you were editing it). Re-read.
        }
        throw IOException("$path kept changing; will retry later")
    }

    companion object {
        private const val TAG = "CaptureWorker"
        private const val WORK = "capture-flush"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private val flushLock = Mutex()

        /** Start (or restart, cancelling any long backoff) a flush of the queue. */
        fun kick(context: Context) {
            if (CaptureQueue(context).count() == 0) return
            val req = OneTimeWorkRequestBuilder<CaptureWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        }

        private fun notifySignIn(context: Context, pending: Int) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Quick capture sync", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_capture)
                .setContentTitle("Sign in to SilverBullet")
                .setContentText("$pending quick capture${if (pending == 1) "" else "s"} waiting — open the app and log in (tick Remember me), or set an API token in settings.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }

        private fun cancelNotification(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
