package com.phaohn.spyhelper

/**
 * Khớp tuyệt đối từng ký tự — dùng cho tra cứu và kiểm tra trùng khi ghi DB.
 * Phân biệt hoa/thường, dấu, khoảng trắng; không trim, không chuẩn hóa.
 * Trùng cặp từ chỉ khi **cả** dân thường **và** gián điệp đều khớp hoàn toàn.
 */
object WordMatcher {
    fun matches(stored: String, input: String): Boolean = stored == input

    fun isDuplicatePair(
        existingCivilian: String,
        existingSpy: String,
        civilian: String,
        spy: String,
    ): Boolean = matches(existingCivilian, civilian) && matches(existingSpy, spy)
}