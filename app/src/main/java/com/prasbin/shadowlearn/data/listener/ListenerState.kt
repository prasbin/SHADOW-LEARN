package com.prasbin.shadowlearn.data.listener

/**
 * Phase 7 Listener Mode session state machine.
 *
 * Explicit, deterministic, UI-agnostic: every transition the Listener screen
 * can be in is named here, and [canTransition] is the single gate the
 * repository enforces (invalid transitions throw instead of silently
 * corrupting a persisted session).
 *
 * ```
 * IDLE ──► REQUESTING_PERMISSION ──► RECORDING ◄──► PAUSED
 *   ▲              │                      │  │          │  │
 *   │              ▼                      ▼  ▼          ▼  ▼
 *   └──────────── IDLE ◄── COMPLETED ◄────┴──┴──────────┴──┴── (stop)
 *   └── ERROR ◄── any active state on recorder failure ──┘
 * ```
 *
 * COMPLETED never returns to RECORDING: a finished session is immutable and
 * a new recording always opens a new session row.
 */
enum class ListenerState {
    IDLE,
    REQUESTING_PERMISSION,
    RECORDING,
    PAUSED,
    COMPLETED,
    ERROR;

    companion object {
        private val ALLOWED: Map<ListenerState, Set<ListenerState>> = mapOf(
            IDLE to setOf(REQUESTING_PERMISSION, RECORDING),
            REQUESTING_PERMISSION to setOf(RECORDING, IDLE, ERROR),
            RECORDING to setOf(PAUSED, COMPLETED, ERROR),
            PAUSED to setOf(RECORDING, COMPLETED, ERROR),
            COMPLETED to setOf(IDLE),
            ERROR to setOf(IDLE)
        )

        /** Whether [from] → [to] is a legal transition. Pure, unit-tested. */
        fun canTransition(from: ListenerState, to: ListenerState): Boolean =
            ALLOWED[from]?.contains(to) == true

        /** Gate a transition; throws [IllegalStateException] when illegal. */
        fun requireTransition(from: ListenerState, to: ListenerState) {
            if (!canTransition(from, to)) {
                throw IllegalStateException("Illegal listener transition: $from -> $to")
            }
        }
    }
}
