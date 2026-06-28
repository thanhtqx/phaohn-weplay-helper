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
)