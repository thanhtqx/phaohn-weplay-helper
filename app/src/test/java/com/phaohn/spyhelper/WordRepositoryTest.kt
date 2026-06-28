package com.phaohn.spyhelper

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WordRepositoryTest {

    private lateinit var db: WordDatabase
    private lateinit var repository: WordRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, WordDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WordRepository(db.wordDao(), db.historyDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun lookupMyTamReturnsAllSpyPairs() = runBlocking {
        val spies = listOf("1", "2", "3", "4", "5", "66", "6")
        repository.importPairs(spies.map { "Mỹ Tâm" to it })

        val result = repository.lookupOthers("Mỹ Tâm")
        assertTrue(result is LookupResult.Found)
        result as LookupResult.Found

        assertEquals("Mỹ Tâm", result.myWord)
        assertEquals(WordRole.CIVILIAN, result.myRole)
        assertEquals(spies.toSet(), result.otherWords.map { it.word }.toSet())
        assertTrue(result.otherWords.all { it.role == WordRole.SPY })
    }
}