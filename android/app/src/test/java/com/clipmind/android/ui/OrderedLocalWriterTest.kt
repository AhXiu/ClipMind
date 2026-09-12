package com.clipmind.android.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedLocalWriterTest {
    @Test fun initializationFinishesBeforeSharedTextIsAppended() = runTest {
        val ready = CompletableDeferred<Unit>()
        var draft = ""
        val writer = OrderedLocalWriter(backgroundScope, {}, { ready.await(); draft = "restored" })
        writer.enqueue { draft += "+shared" }
        runCurrent()
        assertEquals("", draft)
        ready.complete(Unit); runCurrent()
        assertEquals("restored+shared", draft)
    }

    @Test fun lateAutosaveCannotRunAfterSaveAndClear() = runTest {
        val writing = CompletableDeferred<Unit>()
        var persisted = ""
        val writer = OrderedLocalWriter(backgroundScope, {})
        writer.enqueue { writing.await(); persisted = "draft" }
        writer.enqueue { assertEquals("draft", persisted); persisted = "" }
        runCurrent()
        assertEquals("", persisted)
        writing.complete(Unit); runCurrent()
        assertEquals("", persisted)
    }

    @Test fun newTextAfterNoteSaveRemainsADraft() = runTest {
        var persisted = ""
        val writer = OrderedLocalWriter(backgroundScope, {})
        writer.enqueue { persisted = "first" }
        writer.enqueue { persisted = "" }
        writer.enqueue { persisted = "new input" }
        runCurrent()
        assertEquals("new input", persisted)
    }

    @Test fun failedWriteReportsErrorAndAllowsRetry() = runTest {
        var errors = 0
        var saved = false
        val writer = OrderedLocalWriter(backgroundScope, { errors++ })
        writer.enqueue { error("disk unavailable") }
        writer.enqueue { saved = true }
        runCurrent()
        assertEquals(1, errors)
        assertTrue(saved)
    }
}
