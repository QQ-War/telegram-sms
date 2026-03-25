package com.qwe7002.telegram_sms

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.google.gson.Gson
import com.qwe7002.telegram_sms.data_structure.telegram.RequestMessage
import com.qwe7002.telegram_sms.static_class.Other
import com.qwe7002.telegram_sms.static_class.SnowFlake
import com.qwe7002.telegram_sms.static_class.TelegramApi
import java.util.concurrent.Executors

class TelegramSendJob : JobService() {
    companion object {
        private const val TAG = "TelegramSendJob"
        private const val EXTRA_REQUEST_BODY = "request_body"
        private const val EXTRA_FALLBACK_SUB_ID = "fallback_sub_id"
        private const val EXTRA_METHOD = "method"
        private const val EXTRA_PHONE = "phone"
        private const val EXTRA_SLOT = "slot"
        private val gson = Gson()
        private val executor = Executors.newSingleThreadExecutor()

        fun startJob(
            context: Context,
            requestBody: RequestMessage,
            method: String = "sendMessage",
            fallbackSubId: Int = -1,
            phone: String? = null,
            slot: Int = -1
        ) {
            val jobScheduler = context.getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobId = SnowFlake.generate().toString().takeLast(9).toIntOrNull() ?: 0

            val extras = PersistableBundle().apply {
                putString(EXTRA_REQUEST_BODY, gson.toJson(requestBody))
                putString(EXTRA_METHOD, method)
                putInt(EXTRA_FALLBACK_SUB_ID, fallbackSubId)
                if (phone != null) {
                    putString(EXTRA_PHONE, phone)
                }
                putInt(EXTRA_SLOT, slot)
            }

            val jobInfo = JobInfo.Builder(
                jobId,
                ComponentName(context.packageName, TelegramSendJob::class.java.name)
            )
                .setPersisted(true)
                .setExtras(extras)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL) // 30s initial backoff
                .build()

            jobScheduler.schedule(jobInfo)
            Log.d(TAG, "Scheduled TelegramSendJob with ID: $jobId")
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "TelegramSendJob started execution, ID: ${params?.jobId}")
        val extras = params?.extras ?: return false
        val requestBodyJson = extras.getString(EXTRA_REQUEST_BODY, "") ?: ""
        val method = extras.getString(EXTRA_METHOD, "sendMessage") ?: "sendMessage"
        val fallbackSubId = extras.getInt(EXTRA_FALLBACK_SUB_ID, -1)
        val phone = extras.getString(EXTRA_PHONE, null)
        val slot = extras.getInt(EXTRA_SLOT, -1)

        if (requestBodyJson.isEmpty()) {
            return false
        }

        executor.execute {
            val requestBody = gson.fromJson(requestBodyJson, RequestMessage::class.java)
            val result = TelegramApi.sendMessageSync(
                context = applicationContext,
                requestBody = requestBody,
                method = method,
                errorTag = TAG,
                fallbackSubId = fallbackSubId,
                enableResend = false // We handle resend via JobScheduler retry
            )
            if (result != null) {
                Log.i(TAG, "Message sent successfully through JobService")
                if (phone != null && Other.isPhoneNumber(phone)) {
                    Other.addMessageList(Other.getMessageId(result), phone, slot)
                }
                jobFinished(params, false)
            } else {
                Log.e(TAG, "Failed to send message through JobService, will retry with backoff")
                // true means we want the job to be rescheduled with backoff
                jobFinished(params, true)
            }
        }

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true // Always retry if interrupted
}
