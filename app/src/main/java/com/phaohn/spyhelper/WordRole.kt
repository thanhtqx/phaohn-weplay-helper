package com.phaohn.spyhelper

enum class WordRole {
    CIVILIAN,
    SPY,
    ;

    companion object {
        fun fromName(name: String?): WordRole? = when (name) {
            CIVILIAN.name -> CIVILIAN
            SPY.name -> SPY
            else -> null
        }
    }
}

data class LabeledWord(
    val word: String,
    val role: WordRole,
)