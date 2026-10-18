package com.theacar.android.navigation

import com.theacar.model.Coordinate
import com.theacar.route.Destination
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentDestinationsTest {
    private fun destination(id: String, title: String = "場所$id") = Destination(id, title, Coordinate(35.0, 139.0))

    @Test fun newestFirstAndCapped() {
        val list = (1..7).fold(emptyList<Destination>()) { acc, i -> acc.withRecent(destination("$i")) }
        assertEquals(listOf("7", "6", "5", "4", "3"), list.map { it.id })
    }

    @Test fun duplicateMovesToFrontWithLatestValue() {
        val list = listOf(destination("a"), destination("b"), destination("c")).withRecent(destination("c", "新しい名前"))
        assertEquals(listOf("c", "a", "b"), list.map { it.id })
        assertEquals("新しい名前", list.first().title)
    }

    @Test fun roundTripKeepsSpecialCharacters() {
        val list = listOf(Destination("osm:1,2\n3", "東京, 駅\t|\"x\"", Coordinate(35.681236, 139.767125)), destination("b"))
        assertEquals(list, decodeRecentDestinations(encodeRecentDestinations(list)))
    }

    @Test fun decodeSkipsBrokenLinesAndDuplicatesAndCaps() {
        val valid = encodeRecentDestinations(listOf(destination("a"), destination("a"), destination("b")))
        assertEquals(listOf("a", "b"), decodeRecentDestinations("broken\n$valid\n,,,\n1,2,x,y").map { it.id })
        assertEquals(emptyList(), decodeRecentDestinations(""))
        assertEquals(2, decodeRecentDestinations(encodeRecentDestinations((1..5).map { destination("$it") }), 2).size)
    }
}
