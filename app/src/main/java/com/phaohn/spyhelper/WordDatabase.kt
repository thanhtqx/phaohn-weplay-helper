package com.phaohn.spyhelper

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WordPair::class, LookupHistory::class],
    version = 5,
    exportSchema = false,
)
abstract class WordDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun historyDao(): LookupHistoryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lookup_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        myWord TEXT NOT NULL,
                        otherWords TEXT NOT NULL,
                        playedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE word_pairs
                    ADD COLUMN serverSynced INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE word_pairs SET serverSynced = 1 WHERE pairSource = 'sync'
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE word_pairs
                    ADD COLUMN approvalStatus TEXT NOT NULL DEFAULT 'approved'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE word_pairs
                    ADD COLUMN pairSource TEXT NOT NULL DEFAULT 'manual'
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM word_pairs
                    WHERE id NOT IN (
                        SELECT MIN(id) FROM word_pairs GROUP BY civilianWord, spyWord
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_word_pairs_civilianWord_spyWord
                    ON word_pairs (civilianWord, spyWord)
                    """.trimIndent()
                )
            }
        }

        @Volatile private var instance: WordDatabase? = null

        fun get(context: Context): WordDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WordDatabase::class.java,
                    "spy_words.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}