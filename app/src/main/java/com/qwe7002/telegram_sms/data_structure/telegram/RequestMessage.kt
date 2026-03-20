package com.qwe7002.telegram_sms.data_structure.telegram

import com.google.gson.annotations.SerializedName
import com.qwe7002.telegram_sms.data_structure.telegram.ReplyMarkupKeyboard.KeyboardMarkup

class RequestMessage {
    //Turn off page preview to avoid being tracked
    @Suppress("unused")
    @SerializedName(value = "disable_web_page_preview")
    val disableWebPagePreview: Boolean = true
    @SerializedName(value = "message_id")
    var messageId: Long = 0
    @SerializedName(value = "parse_mode")
    var parseMode: String = ""
    @SerializedName(value = "chat_id")
    var chatId: String = ""
    @SerializedName(value = "text")
    var text: String = ""
    @SerializedName(value = "message_thread_id")
    var messageThreadId: String = ""
    @SerializedName(value = "reply_markup")
    var replyMarkup: KeyboardMarkup? = null

    @SerializedName(value = "disable_notification")
    var disableNotification: Boolean = false
}
