package com.clipmind.android.data

fun OutboxState.canTransitionTo(next: OutboxState): Boolean = when (this) {
    OutboxState.PENDING_CONFIRMATION -> next == OutboxState.READY || next == OutboxState.DISCARDED
    OutboxState.UPLOADING -> next == OutboxState.SUCCEEDED || next == OutboxState.REJECTED || next == OutboxState.RETRYABLE_ERROR
    OutboxState.READY -> next == OutboxState.UPLOADING || next == OutboxState.DECRYPTION_FAILED
    OutboxState.RETRYABLE_ERROR -> next == OutboxState.UPLOADING || next == OutboxState.DECRYPTION_FAILED
    OutboxState.SUCCEEDED, OutboxState.DISCARDED, OutboxState.REJECTED, OutboxState.DECRYPTION_FAILED -> false
}
