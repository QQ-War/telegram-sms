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
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.qwe7002.telegram_sms.R
import com.qwe7002.telegram_sms.data_structure.GitHubRelease
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
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
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "telegram-sms-update.apk")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val downloadUri = downloadManager.getUriForDownloadedFile(downloadId)
                    if (downloadUri != null) {
                        installApk(ctx, downloadUri)
                    } else {
                        Log.e(TAG, "Download completed but URI is null for id=$downloadId")
                    }
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

    private fun installApk(context: Context, apkUri: Uri) {
        val installUri = when (apkUri.scheme) {
            "content" -> apkUri
            "file" -> {
                val filePath = apkUri.path ?: return
                val file = java.io.File(filePath)
                if (!file.exists()) return
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            else -> {
                Log.e(TAG, "Unsupported APK URI scheme: ${apkUri.scheme}")
                return
            }
        }

        // 手动拉起安装
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(installUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Standard installation failed: ${e.message}")
        }
    }

}
