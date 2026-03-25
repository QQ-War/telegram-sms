package com.qwe7002.telegram_sms.static_class;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.qwe7002.telegram_sms.config.proxy;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.paperdb.Paper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class supabase_func {
    private static final String TAG = "supabase_func";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static void process_sms(Context context, String content, String from) {
        SharedPreferences prefs = context.getSharedPreferences("supabase", Context.MODE_PRIVATE);
        String url = prefs.getString("url", "");
        String apiKey = prefs.getString("api_key", "");
        String tableName = prefs.getString("table_name", "financial_notifications");

        if (url == null || apiKey == null || url.isEmpty() || apiKey.isEmpty()) {
            return;
        }

        String timeBucket = new SimpleDateFormat("yyyyMMddHH", Locale.getDefault()).format(new Date());
        String externalId = sha256(from + content + timeBucket);

        JSONObject record = parse_financial_notification(content);
        record.put("source", "sms_bot");
        record.put("external_id", externalId);
        record.put("raw_content", content);

        if (!record.has("amount") || record.optDouble("amount", 0.0) <= 0.0) {
            Log.d(TAG, "Not a financial notification, skipping.");
            return;
        }

        insert_record(context, url, apiKey, tableName, record);
    }

    public static void test_connection(Context context, String url, String apiKey, String tableName, Callback callback) {
        String fullUrl = url.replaceAll("/+$", "") + "/rest/v1/" + tableName + "?select=*&limit=1";
        OkHttpClient client = network_func.get_okhttp_obj(
                context.getSharedPreferences("data", Context.MODE_PRIVATE).getBoolean("doh_switch", true),
                Paper.book("system_config").read("proxy_config", new proxy())
        );
        Request request = new Request.Builder()
                .url(fullUrl)
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        new Thread(() -> {
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    callback.onResult(true, "Success");
                } else {
                    callback.onResult(false, "HTTP " + response.code());
                }
            } catch (Exception e) {
                callback.onResult(false, e.getMessage() == null ? "Unknown error" : e.getMessage());
            }
        }).start();
    }

    private static JSONObject parse_financial_notification(String content) {
        JSONObject res = new JSONObject();

        Pattern amountPattern = Pattern.compile("(?:消费|支付|支出|金额|付款|存入|收入|结存|预付款)(?:人民币|￥|\\$)?\\s*([0-9,]+\\.[0-9]{2}|[0-9,]+)\\s*([a-zA-Z]{3}|元|人民币|美元|美金|港币|欧元|日元|英镑|澳元|加元|新元|新加坡元)?");
        Matcher mAmount = amountPattern.matcher(content);
        if (mAmount.find()) {
            String amountStr = mAmount.group(1) == null ? "0" : mAmount.group(1).replace(",", "");
            try {
                res.put("amount", Double.parseDouble(amountStr));
            } catch (Exception e) {
                res.put("amount", 0.0);
            }
            String currencyPart = mAmount.group(2) == null ? "CNY" : mAmount.group(2);
            String normalizedCurrency;
            switch (currencyPart) {
                case "元":
                case "人民币":
                    normalizedCurrency = "CNY";
                    break;
                case "美元":
                case "美金":
                    normalizedCurrency = "USD";
                    break;
                case "港币":
                    normalizedCurrency = "HKD";
                    break;
                case "欧元":
                    normalizedCurrency = "EUR";
                    break;
                case "日元":
                    normalizedCurrency = "JPY";
                    break;
                case "英镑":
                    normalizedCurrency = "GBP";
                    break;
                case "澳元":
                    normalizedCurrency = "AUD";
                    break;
                case "加元":
                    normalizedCurrency = "CAD";
                    break;
                case "新元":
                case "新加坡元":
                    normalizedCurrency = "SGD";
                    break;
                default:
                    normalizedCurrency = currencyPart.toUpperCase();
                    break;
            }
            res.put("currency", normalizedCurrency);
        }

        Pattern merchantPattern = Pattern.compile("(?:向|于|在|给)\\s*([^\\s，。、]{2,20}?)\\s*(?:支付|消费|付款|转账|购买)");
        Matcher mMerchant = merchantPattern.matcher(content);
        if (mMerchant.find()) {
            res.put("merchant", mMerchant.group(1));
        } else {
            Pattern bracketPattern = Pattern.compile("【(.*?)】");
            Matcher bMatcher = bracketPattern.matcher(content);
            if (bMatcher.find()) {
                res.put("merchant", bMatcher.group(1));
            } else {
                res.put("merchant", "Unknown");
            }
        }

        Pattern cardPattern = Pattern.compile("(?:尾号|卡号|账户)\\s*([0-9]{4})");
        Matcher mCard = cardPattern.matcher(content);
        if (mCard.find()) {
            res.put("card_info", mCard.group(1));
        }

        String cleanedContent = content.replace("实际消费金额以入账结算币种金额为准。", "");
        if (cleanedContent.contains("失败") || cleanedContent.contains("拒绝") || cleanedContent.contains("不足")) {
            res.put("category", "reject");
            res.put("status", "failed");
        } else if (cleanedContent.contains("存入") || cleanedContent.contains("收入") || cleanedContent.contains("入账") || cleanedContent.contains("工资")) {
            res.put("category", "income");
        } else if (cleanedContent.contains("还款") || cleanedContent.contains("互转") || cleanedContent.contains("转账")) {
            res.put("category", "transfer");
        } else {
            res.put("category", "consumption");
            res.put("status", "success");
        }

        res.put("description", content.contains("预付款") ? "预付款/消费" : "短信通知");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        res.put("transaction_time", sdf.format(new Date()));

        return res;
    }

    private static void insert_record(Context context, String baseUrl, String apiKey, String tableName, JSONObject data) {
        String fullUrl = baseUrl.replaceAll("/+$", "") + "/rest/v1/" + tableName;
        OkHttpClient client = network_func.get_okhttp_obj(
                context.getSharedPreferences("data", Context.MODE_PRIVATE).getBoolean("doh_switch", true),
                Paper.book("system_config").read("proxy_config", new proxy())
        );
        RequestBody body = RequestBody.create(data.toString(), JSON);
        Request request = new Request.Builder()
                .url(fullUrl)
                .post(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build();

        new Thread(() -> {
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Failed to insert: " + response.code() + " " + response.message());
                } else {
                    Log.i(TAG, "Inserted record successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "Insert error", e);
            }
        }).start();
    }

    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public interface Callback {
        void onResult(boolean success, String msg);
    }
}
