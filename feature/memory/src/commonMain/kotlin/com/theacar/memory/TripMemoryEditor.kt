package com.theacar.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TripMemoryRepository {
    suspend fun update(id: String, transform: (TripMemory) -> TripMemory): TripMemory?
}

data class TripMemoryEditState(val memory: TripMemory, val placeInput: String, val memberInput: String = "")

/** 入力の確定を直列化する。画面を離れる操作と書き出しが重なっても、名前を二重に追加しない。 */
class TripMemoryEditor(initial: TripMemory, private val repository: TripMemoryRepository) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TripMemoryEditState(initial, initial.placeName.orEmpty()))
    val state = mutableState.asStateFlow()

    fun enterPlace(value: String) { mutableState.update { it.copy(placeInput = value) } }
    fun enterMember(value: String) { mutableState.update { it.copy(memberInput = value) } }

    suspend fun commit(): TripMemory = mutex.withLock {
        // ファイル更新後に画面が破棄されても、入力を消すところまでを一組で完了させる。
        withContext(NonCancellable) {
            val draft = state.value
            val updated = requireNotNull(repository.update(draft.memory.id) {
                it.copy(placeName = placeNameInput(draft.placeInput), members = addMember(it.members, draft.memberInput))
            }) { "記録が見つかりません" }
            mutableState.update {
                it.copy(memory = updated, memberInput = if (it.memberInput == draft.memberInput) "" else it.memberInput)
            }
            updated
        }
    }

    suspend fun update(transform: (TripMemory) -> TripMemory): TripMemory = mutex.withLock {
        withContext(NonCancellable) {
            val updated = requireNotNull(repository.update(state.value.memory.id, transform)) { "記録が見つかりません" }
            mutableState.update { it.copy(memory = updated) }
            updated
        }
    }
}
