package com.phaohn.spyhelper

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LookupHistoryDao {
    @Insert
    suspend fun insert(entry: LookupHistory): Long

    @Query("SELECT * FROM lookup_history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 80): List<LookupHistory>

    @Query("DELETE FROM lookup_history WHERE id IN (SELECT id FROM lookup_history ORDER BY playedAt ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("SELECT COUNT(*) FROM lookup_history")
    suspend fun count(): Int

    @Query("DELETE FROM lookup_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM lookup_history")
    suspend fun clearAll()

    @Query(
        """
        SELECT myWord, COUNT(*) AS count
        FROM lookup_history
        GROUP BY myWord
        ORDER BY count DESC, myWord ASC
        LIMIT :limit
        """
    )
    suspend fun topWords(limit: Int): List<LookupWordCount>
}