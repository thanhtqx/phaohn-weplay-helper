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
    fun savePairRejectsSwappedDuplicate() = runBlocking {
        assertTrue(repository.savePair("bếp lửa", "bếp gas"))
        assertEquals(1, repository.pairCount())

        assertTrue(!repository.savePair("bếp gas", "bếp lửa"))
        assertEquals(1, repository.pairCount())
    }

    @Test
    fun addManualReportsSwappedDuplicate() = runBlocking {
        repository.addManual("bếp lửa", "bếp gas")
        val result = repository.addManual("bếp gas", "bếp lửa")
        assertEquals(0, result.added)
        assertEquals(1, result.duplicate)
    }

    @Test
    fun autoSavePathRejectsSwappedDuplicate() = runBlocking {
        repository.savePair("dân", "gián")
        val saved = repository.savePair("gián", "dân")
        assertTrue(!saved)
        assertEquals(1, repository.pairCount())
    }

    @Test
    fun lookupRequiresExactMatch() = runBlocking {
        repository.savePair("Phở", "Bún")
        assertTrue(repository.lookupOthers("Phở") is LookupResult.Found)
        assertTrue(repository.lookupOthers("phở") is LookupResult.NotFound)
        assertTrue(repository.lookupOthers("Phở ") is LookupResult.NotFound)
    }

    @Test
    fun searchPairsRequiresExactMatch() = runBlocking {
        repository.savePair("Phở", "Bún")
        assertEquals(1, repository.searchPairs("Phở").size)
        assertEquals(0, repository.searchPairs("Ph").size)
        assertEquals(0, repository.searchPairs("phở").size)
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