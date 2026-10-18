package com.theacar.android.navigation

import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoutingCredentialsTest {
    private class Storage(var value: String = "") : RoutingTokenStorage {
        var failRead = false
        var failWrite = false
        override fun read(): String {
            check(!failRead)
            return value
        }
        override fun write(token: String) {
            check(!failWrite)
            value = token
        }
    }

    @Test fun restoresSavedTokenOnStartup() = runTest {
        val credentials = RoutingCredentials(Storage("test-token"), backgroundScope, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(credentials.loaded.value)
        assertEquals("test-token", credentials.token.value)
        assertNull(credentials.error.value)
    }

    @Test fun savedTokenSurvivesNewRepositoryInstance() = runTest {
        val storage = Storage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = RoutingCredentials(storage, backgroundScope, io = dispatcher)
        runCurrent()
        assertTrue(first.save(" test-token\n"))
        val second = RoutingCredentials(storage, backgroundScope, io = dispatcher)
        runCurrent()
        assertEquals("test-token", second.token.value)
    }

    @Test fun clearingRemovesSavedAndActiveToken() = runTest {
        val storage = Storage("test-token")
        val credentials = RoutingCredentials(storage, backgroundScope, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(credentials.save(""))
        assertEquals("", credentials.token.value)
        assertEquals("", storage.value)
    }

    @Test fun failedSaveKeepsPreviousTokenAndReportsFailure() = runTest {
        val storage = Storage("previous-token")
        val credentials = RoutingCredentials(storage, backgroundScope, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        storage.failWrite = true
        assertFalse(credentials.save("replacement-token"))
        assertEquals("previous-token", credentials.token.value)
        assertEquals("previous-token", storage.value)
        assertNotNull(credentials.error.value)
        storage.failWrite = false
        assertTrue(credentials.save("replacement-token"))
        assertNull(credentials.error.value)
    }

    @Test fun unreadableCredentialsDoNotCrashAndCanBeReplaced() = runTest {
        val storage = Storage().apply { failRead = true }
        val credentials = RoutingCredentials(storage, backgroundScope, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(credentials.loaded.value)
        assertEquals("", credentials.token.value)
        assertNotNull(credentials.error.value)
        assertTrue(credentials.save("replacement-token"))
        assertNull(credentials.error.value)
    }

    @Test fun immediateSaveIsNotOverwrittenByStartupRestore() = runTest {
        val storage = Storage("previous-token")
        val credentials = RoutingCredentials(storage, backgroundScope, io = StandardTestDispatcher(testScheduler))
        val saving = async { credentials.save("replacement-token") }
        assertTrue(saving.await())
        assertEquals("replacement-token", credentials.token.value)
        assertEquals("replacement-token", storage.value)
    }

    @Test fun builtInTokenIsUsedWhenNothingIsSaved() = runTest {
        val credentials = RoutingCredentials(Storage(), backgroundScope, "built-in-token",
            io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals("built-in-token", credentials.token.value)
        assertTrue(credentials.usingBuiltIn.value)
        assertNull(credentials.error.value)
    }

    @Test fun savedTokenOverridesTheBuiltInOne() = runTest {
        val credentials = RoutingCredentials(Storage("saved-token"), backgroundScope, "built-in-token",
            io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals("saved-token", credentials.token.value)
        assertFalse(credentials.usingBuiltIn.value)
    }

    @Test fun clearingReturnsToTheBuiltInToken() = runTest {
        val storage = Storage("saved-token")
        val credentials = RoutingCredentials(storage, backgroundScope, "built-in-token",
            io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(credentials.save(""))
        assertEquals("built-in-token", credentials.token.value)
        assertTrue(credentials.usingBuiltIn.value)
        assertEquals("", storage.value)
    }

    @Test fun unreadableCredentialsStayQuietWhenTheBuiltInTokenCanBeUsed() = runTest {
        val credentials = RoutingCredentials(Storage().apply { failRead = true }, backgroundScope, "built-in-token",
            io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals("built-in-token", credentials.token.value)
        assertTrue(credentials.usingBuiltIn.value)
        assertNull(credentials.error.value)
    }

    @Test fun aBlankBuiltInTokenLeavesTheDeviceEntryBehaviourUnchanged() = runTest {
        val credentials = RoutingCredentials(Storage(), backgroundScope, "   ",
            io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals("", credentials.token.value)
        assertFalse(credentials.usingBuiltIn.value)
    }
}
