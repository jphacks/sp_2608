package com.theacar.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistInputTest {
    private val id = "a".repeat(22)
    private val song = PlaylistTrack("isrc:JPABC2300001", "Song", "Artist", 180000)

    @Test fun acceptsOnlySpotifyPlaylistIdsAndCanonicalLinks() {
        assertEquals(id, spotifyPlaylistId("https://open.spotify.com/playlist/$id?si=test"))
        assertEquals(id, spotifyPlaylistId("spotify:playlist:$id"))
        assertNull(spotifyPlaylistId("https://other.example/playlist/$id"))
        assertNull(spotifyPlaylistId("https://open.spotify.com.evil/playlist/$id"))
        assertNull(spotifyPlaylistId("https://open.spotify.com/track/$id"))
    }

    @Test fun combinesOwnersWithoutDuplicatingSongsAndReplacesReimportedLists() = runTest {
        val session = PlaylistInputSession(object : PlaylistSource {
            override suspend fun playlist(id: String) = PlaylistTracks(listOf(song, song))
        }, object : PlaylistAnnotator {
            override suspend fun annotate(tracks: List<PlaylistTrack>) = tracks.map { it.trackId }.toSet()
        }, this)
        session.import("A", id); runCurrent()
        session.import("B", id); runCurrent()
        session.import("A", id); runCurrent()
        assertEquals(2, session.state.value.lists.size)
        assertEquals(setOf("p0", "p1"), session.state.value.pool.single().owners.toSet())
        session.remove(id, "p0")
        assertEquals(listOf("p1"), session.state.value.pool.single().owners)
        session.setMode(RecommendationMode.PLAYLIST)
        assertEquals(RecommendationMode.PLAYLIST, session.state.value.mode)
    }

    @Test fun failedAnnotationKeepsPreviousInputAndClearRejectsLateImports() = runTest {
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        val session = PlaylistInputSession(object : PlaylistSource {
            override suspend fun playlist(id: String): PlaylistTracks { gate?.await(); return PlaylistTracks(listOf(song)) }
        }, object : PlaylistAnnotator {
            override suspend fun annotate(tracks: List<PlaylistTrack>) = if (failure) emptySet() else setOf(song.trackId)
        }, this)
        session.import("A", id); runCurrent()
        failure = true
        session.import("B", id); runCurrent()
        assertEquals(listOf("A"), session.state.value.members.map { it.name })
        assertNotNull(session.state.value.error)
        gate = CompletableDeferred()
        session.import("B", id); runCurrent()
        session.clear(); gate?.complete(Unit); runCurrent()
        assertEquals(PlaylistInputState(), session.state.value)
    }

    @Test fun spacesAnnotationBatchesAndStopsAtTheFirstIncompleteBatch() = runTest {
        val tracks = (1..101).map { song.copy(trackId = "isrc:$it") }
        val sizes = mutableListOf<Int>()
        val session = PlaylistInputSession(object : PlaylistSource {
            override suspend fun playlist(id: String) = PlaylistTracks(tracks)
        }, object : PlaylistAnnotator {
            override suspend fun annotate(tracks: List<PlaylistTrack>): Set<String> {
                sizes += tracks.size
                return if (sizes.size == 1) tracks.map { it.trackId }.toSet() else emptySet()
            }
        }, this)
        session.import("A", id); runCurrent()
        assertEquals(listOf(50), sizes)
        assertTrue(session.state.value.importing)
        advanceUntilIdle()
        assertEquals(listOf(50, 50), sizes)
        assertFalse(session.state.value.importing)
        assertTrue(session.state.value.lists.isEmpty())
        assertNotNull(session.state.value.error)
    }
}
