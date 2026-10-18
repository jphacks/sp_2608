package com.theacar.memory

import com.theacar.model.Coordinate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TripMemoryEditorTest {
    private class Repository : TripMemoryRepository {
        var memory = TripMemory("trip", 0, 1_000, 100, 1, "目的地", Coordinate(43.0, 141.0))
        var pending: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun update(id: String, transform: (TripMemory) -> TripMemory): TripMemory {
            pending?.await()
            check(!fail)
            return transform(memory).also { memory = it }
        }
    }

    @Test fun repeatedCommitDoesNotAddTheSameInputAgain() = runTest {
        val repo = Repository()
        val editor = TripMemoryEditor(repo.memory, repo)
        editor.enterMember("葵")
        repeat(3) { editor.commit() }
        assertEquals(listOf("葵"), repo.memory.members)
        assertEquals("", editor.state.value.memberInput)
    }

    @Test fun exportCommitIncludesPlaceAndUnconfirmedMember() = runTest {
        val repo = Repository()
        val editor = TripMemoryEditor(repo.memory, repo)
        editor.enterPlace(" 函館 ")
        editor.enterMember("葵")
        val exported = editor.commit()
        assertEquals("函館", exported.placeName)
        assertEquals(listOf("葵"), exported.members)
        assertEquals(exported, editor.state.value.memory)
    }

    @Test fun overlappingCommitsAreSerializedAndNewInputIsKept() = runTest {
        val repo = Repository().apply { pending = CompletableDeferred() }
        val editor = TripMemoryEditor(repo.memory, repo)
        editor.enterMember("葵")
        val first = async { editor.commit() }
        runCurrent()
        editor.enterMember("凛")
        val second = async { editor.commit() }
        repo.pending!!.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf("葵", "凛"), repo.memory.members)
    }

    @Test fun failedSaveKeepsTheInputForRetry() = runTest {
        val repo = Repository().apply { fail = true }
        val editor = TripMemoryEditor(repo.memory, repo)
        editor.enterMember("葵")
        assertFailsWith<IllegalStateException> { editor.commit() }
        assertEquals("葵", editor.state.value.memberInput)
        repo.fail = false
        editor.commit()
        assertEquals(listOf("葵"), repo.memory.members)
    }

    @Test fun cancellationDuringSaveDoesNotDuplicateTheNextCommit() = runTest {
        val repo = Repository().apply { pending = CompletableDeferred() }
        val editor = TripMemoryEditor(repo.memory, repo)
        editor.enterMember("葵")
        val saving = launch { editor.commit() }
        runCurrent()
        saving.cancel()
        repo.pending!!.complete(Unit)
        saving.join()
        editor.commit()
        assertEquals(listOf("葵"), repo.memory.members)
        assertEquals("", editor.state.value.memberInput)
    }
}
