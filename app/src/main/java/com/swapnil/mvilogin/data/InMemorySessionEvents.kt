package com.swapnil.mvilogin.data

import com.swapnil.mvilogin.domain.model.SessionEvent
import com.swapnil.mvilogin.domain.repository.SessionEvents
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SHARED FLOW — the right tool for a BROADCAST: every active subscriber receives
 * every event (fan-out). Compare:
 *  - StateFlow : always has a current value, conflates, skips equal values -> "state"
 *  - Channel   : each element goes to exactly ONE receiver                -> "one-shot to one consumer"
 *  - SharedFlow: each element goes to ALL current subscribers             -> "broadcast"
 *
 * Parameters:
 *  replay = 0                -> late subscribers don't get old events (an old "session expired"
 *                               should not pop up on a screen opened later)
 *  extraBufferCapacity = 8   -> emit() doesn't suspend while slow subscribers catch up
 *  DROP_OLDEST               -> if 8 events pile up, drop the oldest instead of suspending publishers
 *
 * Space: O(replay + extraBufferCapacity) per flow. emit: O(number of subscribers).
 */
@Singleton
class InMemorySessionEvents @Inject constructor() : SessionEvents {

    private val _events = MutableSharedFlow<SessionEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    override suspend fun publish(event: SessionEvent) {
        _events.emit(event)
    }

    // With extraBufferCapacity + DROP_OLDEST, tryEmit always succeeds (never needs to suspend).
    override fun tryPublish(event: SessionEvent): Boolean = _events.tryEmit(event)
}
