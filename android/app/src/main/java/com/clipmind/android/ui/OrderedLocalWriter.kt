package com.clipmind.android.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Local writes keep their order and never wait on a remote AI task. */
internal class OrderedLocalWriter(scope: CoroutineScope, onError: () -> Unit, initialize: suspend () -> Unit = {}) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        scope.launch {
            try {
                initialize()
                for (command in commands) {
                    try { command() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onError() }
                }
            } finally { commands.cancel() }
        }
    }
    fun enqueue(command: suspend () -> Unit) { commands.trySend(command) }
}
