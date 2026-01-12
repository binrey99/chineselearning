package com.example.chineselearning

object PinyinHelper {
    // Dữ liệu có sẵn trong máy, không cần mạng
    private val pinyinMap = mapOf(
        "Room" to "kè fáng",
        "Television" to "diàn shì",
        "Cat" to "māo",
        "Table" to "zhuō zi"
        // Thêm các từ khác tại đây...
    )

    fun getPinyin(englishTitle: String, callback: (String) -> Unit) {
        val pinyin = pinyinMap[englishTitle] ?: "Đang cập nhật..."
        callback(pinyin)
    }
}