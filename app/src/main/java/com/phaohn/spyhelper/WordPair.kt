package com.phaohn.spyhelper

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_pairs",
    indices = [
        Index(value = ["civilianWord"]),
        Index(value = ["spyWord"]),
        Index(value = ["civilianWord", "spyWord"], unique = true),
    ]
)
data class WordPair(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val civilianWord: String,
    val spyWord: String,
    val savedAt: Long = System.currentTimeMillis(),
    /** approved = dùng tra cứu/đồng bộ; pending = chờ admin duyệt */
    val approvalStatus: String = STATUS_APPROVED,
    /** manual | capture | import | sync — gửi khi đồng bộ lên server */
    val pairSource: String = SOURCE_MANUAL,
    /** Đã có trên server — không đẩy lại khi đồng bộ */
    val serverSynced: Boolean = false,
) {
    val isPendingApproval: Boolean get() = approvalStatus == STATUS_PENDING
    val isApproved: Boolean get() = approvalStatus == STATUS_APPROVED

    companion object {
        const val STATUS_APPROVED = "approved"
        const val STATUS_PENDING = "pending"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_CAPTURE = "capture"
        const val SOURCE_IMPORT = "import"
        const val SOURCE_SYNC = "sync"
    }
}