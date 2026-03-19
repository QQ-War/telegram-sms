package com.qwe7002.telegram_sms.static_class

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.qwe7002.telegram_sms.R
import com.qwe7002.telegram_sms.data_structure.GitHubRelease
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.File
import java.io.IOException

object Update {
    private const val TAG = "Update"

    fun checkAndDownload(context: Context, callback: (Boolean, String) -> Unit) {
        val packageManager = context.packageManager
        val packageInfo: PackageInfo
        val currentVersionName: String
        try {
            packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            currentVersionName = packageInfo.versionName.toString()
        } catch (e: PackageManager.NameNotFoundException) {
            callback(false, "Failed to get package info: ${e.message}")
            return
        }

        val requestUri = String.format(
            "https://api.github.com/repos/QQ-War/%s/releases/tags/nightly",
            context.getString(R.string.app_identifier)
        )
        val request: Request = Request.Builder().url(requestUri).build()
        val client = Network.getOkhttpObj(false)

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, "GitHub API error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(false, "No update found (HTTP ${it.code})")
                        return
                    }
                    val body = it.body.string()
                    val release = Gson().fromJson(body, GitHubRelease::class.java)
                    
                    if (release.name != currentVersionName) {
                        val downloadUrl = release.assets.find { asset -> 
                            asset.name.endsWith(".apk") 
                        }?.browserDownloadUrl
                        
                        if (downloadUrl != null) {
                            downloadAndInstall(context, downloadUrl, release.name)
                            callback(true, "New version found: ${release.name}. Starting download...")
                        } else {
                            callback(false, "No APK found in release assets.")
                        }
                    } else {
                        callback(false, "Current version is up to date: $currentVersionName")
                    }
                }
            }
        })
    }

    private fun downloadAndInstall(context: Context, url: String, versionName: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri).apply {
            setTitle("Telegram-SMS Update: $versionName")
            setDescription("Downloading latest nightly build")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, null, "update.apk")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val file = File(ctx.getExternalFilesDir(null), "update.apk")
                    installApk(ctx, file)
                    ctx.unregisterReceiver(this)
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, file: File) {
        if (!file.exists()) return

        // 尝试使用 Shizuku 执行静默安装
        if (isShizukuAvailable()) {
            Log.i(TAG, "Shizuku available, attempting silent install...")
            try {
                // 使用正确的 Shizuku API 进行调用
                val command = "pm install -r \"${file.absolutePath}\""
                val process = runShizukuCommand(command)
                if (process != null) {
                    Thread {
                        val exitCode = process.waitFor()
                        Log.i(TAG, "Shizuku silent install exited with code: $exitCode")
                    }.start()
                    return
                }
                Log.e(TAG, "Shizuku command failed to start, falling back to normal install.")
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku silent install failed: ${e.message}")
            }
        }

        // 回退逻辑
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Standard installation failed: ${e.message}")
        }
    }

    private fun runShizukuCommand(command: String): ShizukuRemoteProcess? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as? ShizukuRemoteProcess
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku newProcess via reflection failed: ${e.message}")
            null
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }
}
