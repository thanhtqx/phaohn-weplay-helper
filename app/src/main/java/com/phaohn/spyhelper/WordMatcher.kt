package com.phaohn.spyhelper

/**
 * Khớp tuyệt đối từng ký tự — dùng cho tra cứu và kiểm tra trùng khi ghi DB.
 * Phân biệt hoa/thường, dấu, khoảng trắng; không trim, không chuẩn hóa.
 * Trùng cặp từ khi hai từ khớp hoàn toàn, kể cả khi chỉ đảo vai dân/gián.
 */
object WordMatcher {
    fun matches(stored: String, input: String): Boolean = stored == input

    fun isDuplicatePair(
        existingCivilian: String,
        existingSpy: String,
        civilian: String,
        spy: String,
    ): Boolean {
        val sameOrder = matches(existingCivilian, civilian) && matches(existingSpy, spy)
        val swappedOrder = matches(existingCivilian, spy) && matches(existingSpy, civilian)
        return sameOrder || swappedOrder
    }
}