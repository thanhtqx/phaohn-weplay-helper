package com.phaohn.spyhelper

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lookup_history")
data class LookupHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val myWord: String,
    val otherWords: String,
    val playedAt: Long = System.currentTimeMillis(),
)

data class LookupWordCount(
    val myWord: String,
    val count: Int,
)