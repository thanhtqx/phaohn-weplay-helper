package com.phaohn.spyhelper

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pair: WordPair): Long

    @Query("SELECT * FROM word_pairs ORDER BY savedAt DESC")
    suspend fun getAll(): List<WordPair>

    @Query("SELECT COUNT(*) FROM word_pairs")
    suspend fun countAll(): Int

    @Query("UPDATE word_pairs SET civilianWord = :c, spyWord = :s WHERE id = :id")
    suspend fun update(id: Long, c: String, s: String)

    @Query(
        """
        UPDATE word_pairs
        SET approvalStatus = :status, pairSource = :source
        WHERE id = :id
        """,
    )
    suspend fun updateApproval(id: Long, status: String, source: String)

    @Query("DELETE FROM word_pairs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE word_pairs SET serverSynced = 1 WHERE id = :id")
    suspend fun markServerSynced(id: Long)

    @Query("UPDATE word_pairs SET serverSynced = 0 WHERE id = :id")
    suspend fun markServerUnsynced(id: Long)

    @Query("DELETE FROM word_pairs")
    suspend fun clearAll()
}