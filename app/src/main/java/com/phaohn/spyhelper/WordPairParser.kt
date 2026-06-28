package com.phaohn.spyhelper

object WordPairParser {

    /** Mỗi dòng: trái = dân thường, phải = gián điệp — phân tách bởi dấu phẩy. Không trim. */
    fun parseCommaLines(text: String): PairParseResult {
        val pairs = mutableListOf<Pair<String, String>>()
        var invalid = 0
        for (line in text.lines().filter { it.isNotEmpty() }) {
            val comma = line.indexOf(',')
            if (comma <= 0 || comma >= line.lastIndex) {
                invalid++
                continue
            }
            val civilian = line.substring(0, comma)
            val spy = line.substring(comma + 1)
            if (civilian.isEmpty() || spy.isEmpty()) {
                invalid++
            } else {
                pairs += civilian to spy
            }
        }
        return PairParseResult(pairs, invalid)
    }
}