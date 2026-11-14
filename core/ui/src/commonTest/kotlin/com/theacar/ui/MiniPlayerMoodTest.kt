package com.theacar.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MiniPlayerMoodTest {
    @Test fun scenesFromBgmApiMapToTheSceneryColors() {
        assertEquals(MiniPlayerMood.SEA, MiniPlayerMood.ofScene("sea"))
        assertEquals(MiniPlayerMood.FOREST, MiniPlayerMood.ofScene("valley"))
        assertEquals(MiniPlayerMood.WATER, MiniPlayerMood.ofScene("river"))
        assertEquals(MiniPlayerMood.NATURE, MiniPlayerMood.ofScene("farmland"))
        assertEquals(MiniPlayerMood.TOWN, MiniPlayerMood.ofScene("townscape"))
    }

    @Test fun unknownScenesStayPlain() {
        assertEquals(MiniPlayerMood.PLAIN, MiniPlayerMood.ofScene(null))
        assertEquals(MiniPlayerMood.PLAIN, MiniPlayerMood.ofScene("unclassified"))
    }
}
