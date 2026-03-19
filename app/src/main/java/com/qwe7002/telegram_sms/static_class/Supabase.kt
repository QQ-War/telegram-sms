package com.qwe7002.telegram_sms.static_class

import android.content.Context
import android.util.Log
import com.qwe7002.telegram_sms.MMKV.MMKVConst
import com.tencent.mmkv.MMKV
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

object Supabase {
    private const val TAG = "Supabase"

    fun processSms(context: Context, content: String, from: String) {
        val preferences = MMKV.mmkvWithID(MMKVConst.SUPABASE_ID)
        val url = preferences.getString("url", "") ?: ""
        val apiKey = preferences.getString("api_key", "") ?: ""
        val tableName = preferences.getString("table_name", "financial_notifications") ?: "financial_notifications"

        if (url.isEmpty() || apiKey.isEmpty()) {
            return
        }

        // 外部 ID 去重: SHA256(from + content + 小时时间戳)
        val timeBucket = SimpleDateFormat("yyyyMMddHH", Locale.getDefault()).format(Date())
        val externalId = sha256("$from$content$timeBucket")

        val record = parseFinancialNotification(content)
        record.put("source", "sms_bot")
        record.put("external_id", externalId)
        record.put("raw_content", content)
        
        // 如果解析不到金额，说明不是财务短信
        if (!record.has("amount") || record.getDouble("amount") <= 0.0) {
            Log.d(TAG, "Not a financial notification, skipping.")
            return
        }

        insertRecord(url, apiKey, tableName, record)
    }

    private fun parseFinancialNotification(content: String): JSONObject {
        val res = JSONObject()
        
        // 1. 金额与货币解析
        // 针对 "消费/预付款688.50PEN" 这种格式进行优化
        val amountPattern = Pattern.compile("(?:消费|支付|支出|金额|付款|存入|收入|结存|预付款)(?:人民币|￥|\\$)?\\s*([0-9,]+\\.[0-9]{2}|[0-9,]+)\\s*([a-zA-Z]{3}|元)?")
        val mAmount = amountPattern.matcher(content)
        if (mAmount.find()) {
            val amountStr = mAmount.group(1)?.replace(",", "") ?: "0"
            res.put("amount", amountStr.toDoubleOrNull() ?: 0.0)
            
            // 提取货币单位 (PEN, USD, CNY 等)
            val currencyPart = mAmount.group(2) ?: "CNY"
            res.put("currency", if (currencyPart == "元") "CNY" else currencyPart.uppercase())
        }

        // 2. 商户/交易方解析 (优先匹配 "向/在...支付"，兜底匹配 "【...】")
        val merchantPattern = Pattern.compile("(?:向|于|在|给)\\s*([^\\s，。、]{2,20}?)\\s*(?:支付|消费|付款|转账|购买)")
        val mMerchant = merchantPattern.matcher(content)
        if (mMerchant.find()) {
            res.put("merchant", mMerchant.group(1))
        } else {
            // 兜底提取括号内的银行名/应用名
            val bracketPattern = Pattern.compile("【(.*?)】")
            val bMatcher = bracketPattern.matcher(content)
            if (bMatcher.find()) {
                res.put("merchant", bMatcher.group(1))
            } else {
                res.put("merchant", "Unknown")
            }
        }

        // 3. 卡号/资产信息解析
        val cardPattern = Pattern.compile("(?:尾号|卡号|账户)\\s*([0-9]{4})")
        val mCard = cardPattern.matcher(content)
        if (mCard.find()) {
            res.put("card_info", mCard.group(1))
        }

        // 4. 分类映射
        when {
            content.contains("失败") || content.contains("拒绝") || content.contains("不足") -> {
                res.put("category", "reject")
                res.put("status", "failed")
            }
            content.contains("存入") || content.contains("收入") || content.contains("入账") || content.contains("工资") -> {
                res.put("category", "income")
            }
            content.contains("还款") || content.contains("互转") || content.contains("转账") -> {
                res.put("category", "transfer")
            }
            else -> {
                res.put("category", "consumption")
                res.put("status", "success")
            }
        }

        // 5. 交易描述
        res.put("description", if (content.contains("预付款")) "预付款/消费" else "短信通知")
        
        // 6. 交易时间 (北京时间时区)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("GMT+8")
        res.put("transaction_time", sdf.format(Date()))

        return res
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun testConnection(url: String, apiKey: String, tableName: String, callback: (Boolean, String) -> Unit) {
        val testData = JSONObject()
        testData.put("source", "sms_bot_test")
        testData.put("external_id", "test_" + System.currentTimeMillis())
        testData.put("amount", 688.50)
        testData.put("currency", "PEN")
        testData.put("merchant", "建设银行测试")
        testData.put("category", "consumption")
        testData.put("raw_content", "【建设银行】样例数据测试入库")
        
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("GMT+8")
        testData.put("transaction_time", sdf.format(Date()))

        val fullUrl = "${url.trimEnd('/')}/rest/v1/$tableName"
        val httpUrl = fullUrl.toHttpUrlOrNull()
        if (httpUrl == null) {
            callback(false, "Invalid Supabase URL")
            return
        }
        val client = Network.getOkhttpObj(false)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = testData.toString().toRequestBody(mediaType)

        val request = try {
            Request.Builder()
                .url(httpUrl)
                .post(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
        } catch (e: Exception) {
            callback(false, "Request build failed: ${e.message}")
            return
        }

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                callback(false, e.message ?: "Unknown error")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (it.isSuccessful) callback(true, "Success")
                    else callback(false, "HTTP ${it.code}: ${it.message}")
                }
            }
        })
    }

    private fun insertRecord(baseUrl: String, apiKey: String, tableName: String, data: JSONObject) {
        val fullUrl = "${baseUrl.trimEnd('/')}/rest/v1/$tableName"
        val client = Network.getOkhttpObj(false)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = data.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("apikey", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to insert to Supabase: ${response.code} ${response.message}")
                    } else {
                        Log.i(TAG, "Successfully inserted to Supabase")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting to Supabase", e)
            }
        }.start()
    }
}
