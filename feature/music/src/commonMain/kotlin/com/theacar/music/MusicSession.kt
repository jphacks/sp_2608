package com.theacar.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MusicState(
    val query: String = "",
    val results: List<MusicTrack> = emptyList(),
    val devices: List<MusicDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val playback: MusicPlayback = MusicPlayback(),
    val searching: Boolean = false,
    val busy: Boolean = false,
    val failure: MusicFailure? = null,
)

class MusicSession(private val service: MusicService, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(MusicState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var command: Job? = null
    private var searchGeneration = 0L
    private var commandGeneration = 0L
    private var closed = false

    fun search(query: String) {
        if (closed) return
        searchJob?.cancel()
        val generation = ++searchGeneration
        val text = query.trim()
        mutableState.update { it.copy(query = text, results = emptyList(), searching = text.isNotEmpty(), failure = null) }
        if (text.isEmpty()) return
        searchJob = scope.launch {
            try {
                val tracks = service.search(text)
                if (generation == searchGeneration) mutableState.update { it.copy(results = tracks, searching = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == searchGeneration) mutableState.update { it.copy(searching = false, failure = error.musicFailure()) }
            }
        }
    }

    fun selectDevice(id: String) {
        if (!closed && state.value.devices.any { it.id == id }) mutableState.update { it.copy(selectedDeviceId = id) }
    }

    fun refresh() = perform { updatePlayback(it) }
    fun play(track: MusicTrack) = perform { generation -> service.play(track.uri, device()); updatePlayback(generation) }
    fun togglePlayback() = perform { generation ->
        val selected = device()
        val playback = state.value.playback
        if (playback.playing && playback.deviceId == selected) service.pause(selected)
        else service.play(null, selected)
        updatePlayback(generation)
    }
    fun next() = perform { generation -> service.next(device()); updatePlayback(generation) }
    fun previous() = perform { generation -> service.previous(device()); updatePlayback(generation) }

    suspend fun playMix(tracks: List<MusicTrack>, deviceId: String): Boolean {
        if (closed || state.value.busy) return false
        val result = CompletableDeferred<Boolean>()
        perform { generation ->
            try {
                service.playMix(tracks, deviceId)
                updatePlayback(generation)
                result.complete(true)
            } catch (error: Exception) {
                result.completeExceptionally(error)
                throw error
            } finally { result.complete(false) }
        }
        return result.await()
    }

    private fun device() = state.value.selectedDeviceId ?: throw MusicException(MusicFailure.NO_DEVICE)

    private suspend fun updatePlayback(generation: Long) {
        val devices = service.devices()
        val playback = service.playback()
        if (generation != commandGeneration || closed) return
        mutableState.update { previous ->
            previous.copy(devices = devices, playback = playback,
                selectedDeviceId = previous.selectedDeviceId?.takeIf { id -> devices.any { it.id == id } }
                    ?: devices.firstOrNull { it.active }?.id)
        }
    }

    private fun perform(action: suspend (Long) -> Unit) {
        if (closed || state.value.busy) return
        mutableState.update { it.copy(busy = true, failure = null) }
        val generation = ++commandGeneration
        command = scope.launch {
            try { action(generation) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == commandGeneration) mutableState.update { it.copy(failure = error.musicFailure()) }
            } finally { if (generation == commandGeneration) mutableState.update { it.copy(busy = false) } }
        }
    }

    fun reset() {
        searchGeneration++
        commandGeneration++
        searchJob?.cancel()
        command?.cancel()
        mutableState.value = MusicState()
    }

    fun close() { closed = true; reset() }
}

private fun Exception.musicFailure() = (this as? MusicException)?.failure ?: MusicFailure.NETWORK
