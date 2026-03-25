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
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.File
import java.io.IOException

object Update {
    private const val TAG = "Update"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 10001
    private var pendingInstall: File? = null
    private var pendingContext: Context? = null
    private var installStatusCallback: ((Boolean, String) -> Unit)? = null
    @Volatile private var binderListenerRegistered: Boolean = false

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
                            installStatusCallback = callback
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
        
        // 清理旧的更新文件
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val oldFile = File(publicDir, "telegram-sms-update.apk")
        if (oldFile.exists()) {
            oldFile.delete()
        }

        val request = DownloadManager.Request(uri).apply {
            setTitle("Telegram-SMS Update: $versionName")
            setDescription("Downloading latest nightly build")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "telegram-sms-update.apk")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val file = File(publicDir, "telegram-sms-update.apk")
                    if (file.exists()) {
                        installApk(ctx, file)
                    } else {
                        Log.e(TAG, "Downloaded file not found at: ${file.absolutePath}")
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

    private fun installApk(context: Context, file: File) {
        try {
            if (!file.exists()) return
        } catch (e: Exception) {
            Log.e(TAG, "File check failed: ${e.message}")
            installStatusCallback?.invoke(false, "Update file check failed: ${e.message}")
            return
        }

        // 签名检查失败仅做记录，不强行拦截，由系统安装器做最终决定
        if (!isSignatureMatch(context, file)) {
            Log.w(TAG, "APK signature mismatch detected. System installer may fail.")
        }

        // 尝试使用 Shizuku 执行静默安装
        if (isShizukuAvailable()) {
            Log.i(TAG, "Shizuku available, attempting silent install...")
            try {
                // 使用标准 pm install 命令
                val command = "pm install -r \"${file.absolutePath}\""
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                Thread {
                    val exitCode = process.waitFor()
                    Log.i(TAG, "Shizuku silent install exited with code: $exitCode")
                    if (exitCode != 0) {
                        Log.e(TAG, "Shizuku install failed, falling back to UI install.")
                        mainThreadInstall(context, file)
                    }
                }.start()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku silent install failed: ${e.message}")
            }
        } else if (Shizuku.pingBinder()) {
            // Shizuku 运行中但没权限，申请权限
            pendingInstall = file
            pendingContext = context.applicationContext
            tryRequestShizukuPermission()
            return
        }

        // 回退到普通 UI 安装
        mainThreadInstall(context, file)
    }

    private fun mainThreadInstall(context: Context, file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Standard installation failed: ${e.message}")
            installStatusCallback?.invoke(false, "Standard installation failed: ${e.message}")
        }
    }

    private fun tryRequestShizukuPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return false
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Shizuku permission request failed: ${e.message}")
            false
        }
    }

    init {
        try {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return@addRequestPermissionResultListener
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    val file = pendingInstall
                    val ctx = pendingContext
                    if (file != null && ctx != null) {
                        Log.i(TAG, "Shizuku permission granted, retrying install")
                        pendingInstall = null
                        pendingContext = null
                        installApk(ctx, file)
                    }
                } else {
                    Log.e(TAG, "Shizuku permission denied")
                    // 权限被拒绝，回退到普通安装
                    val file = pendingInstall
                    val ctx = pendingContext
                    if (file != null && ctx != null) {
                        pendingInstall = null
                        pendingContext = null
                        mainThreadInstall(ctx, file)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register Shizuku permission listener: ${e.message}")
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    private fun isSignatureMatch(context: Context, file: File): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            
            val installed = pm.getPackageInfo(context.packageName, flags)
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            
            if (archive == null) return false

            val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installed.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                installed.signatures
            }

            val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archive.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                archive.signatures
            }

            if (installedSignatures == null || archiveSignatures == null) return false
            
            val installedSet = installedSignatures.map { it.toCharsString() }.toSet()
            val archiveSet = archiveSignatures.map { it.toCharsString() }.toSet()
            
            installedSet == archiveSet
        } catch (e: Throwable) {
            Log.e(TAG, "Signature check failed: ${e.message}")
            true // 出错时默认返回 true，交由系统判断，避免误判拦截
        }
    }
}

