/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.key.KeyCode

/**
 * One finger holding a layer key open (issue #366): `?123` down opens the symbols, the same touch
 * slides onto a symbol, and lifting types it and puts the letters back.
 *
 * Only the decisions live here — which layer a key opens, whether the press is still waiting for that
 * layer to arrive, and what to return to when the finger lifts. The touch dispatch itself is
 * [TextKeyboardLayout]'s, which already cancels and re-binds a pointer whenever it leaves the key it
 * is on; this class is what tells it when not to, and what to undo afterwards.
 *
 * A layer key is switched on **down** rather than after a long press, so the layer is there the moment
 * the finger lands and the slide can start immediately. That costs nothing for anyone who only taps:
 * a lift with no other key pressed in between leaves the layer exactly where a tap has always left it.
 */
class MomentaryLayer {
    /** The pointer that opened the layer, or [NO_POINTER] while no layer is held open. */
    var ownerPointerId: Int = NO_POINTER
        private set

    /** The mode the gesture started in — where a lift on another key returns to. */
    var returnMode: KeyboardMode? = null
        private set

    /** The mode the layer key opened. */
    var targetMode: KeyboardMode? = null
        private set

    private var settled: Boolean = false

    val isIdle: Boolean
        get() = targetMode == null

    /**
     * True between the switch and the new keyboard actually arriving. The mode change travels through
     * a state flow and a background coroutine before the layout is recomputed, so for a frame or two
     * the keys under the finger are still the outgoing layer's — re-binding to one of those would
     * press a letter the user can no longer see.
     */
    val isPending: Boolean
        get() = targetMode != null && !settled

    fun owns(pointerId: Int): Boolean = targetMode != null && pointerId == ownerPointerId

    fun begin(pointerId: Int, from: KeyboardMode, to: KeyboardMode) {
        ownerPointerId = pointerId
        returnMode = from
        targetMode = to
        settled = false
    }

    /** Called with every keyboard the layout renders; the wait ends when the asked-for one shows up. */
    fun onKeyboardSettled(mode: KeyboardMode) {
        if (targetMode == mode) {
            settled = true
        }
    }

    /**
     * The mode to restore now that the finger is up, or `null` to leave the keyboard where it is.
     *
     * @param landedCode the key the finger lifted over, or `null` if it lifted over nothing.
     * @param wasUninterrupted whether no other key was pressed while the layer key was held — the same
     *   question [dev.patrickgold.florisboard.ime.input.InputEventDispatcher.isUninterruptedEventSequence]
     *   answers for Shift, and for the same reason: it is what separates a tap that means "switch" from
     *   a hold that means "just this one". It also catches the two-finger version of the gesture, where
     *   the symbol is tapped with a second finger rather than slid onto.
     */
    fun end(landedCode: Int?, wasUninterrupted: Boolean): KeyboardMode? {
        val landedLayer = landedCode?.let { modeFor(it) }
        val restore = when {
            targetMode == null -> null
            wasUninterrupted -> null
            // Landed on a layer key for a *different* layer: the user chose that one out loud, so undoing
            // it would be overruling them, and its own up event has already taken the keyboard there.
            // It has to be a different one — the key still under a finger that never moved is the layer
            // key this gesture started on, and that case is the two-finger version of the gesture
            // (hold ?123, tap € with the other hand), which does want the letters back.
            landedLayer != null && landedLayer != targetMode -> null
            else -> returnMode
        }
        clear()
        return restore
    }

    /** Unconditionally back to where the gesture started — for a press the system took away. */
    fun endCancelled(): KeyboardMode? {
        val restore = returnMode.takeIf { targetMode != null }
        clear()
        return restore
    }

    fun clear() {
        ownerPointerId = NO_POINTER
        returnMode = null
        targetMode = null
        settled = false
    }

    companion object {
        const val NO_POINTER: Int = -1

        /**
         * The layer this key opens, or `null` if it is not a layer key.
         *
         * The emoji key is deliberately absent: it switches `imeUiMode`, not the keyboard mode, and the
         * media panel is a separate composable that the finger's touch stream does not carry over into.
         */
        fun modeFor(code: Int): KeyboardMode? = when (code) {
            KeyCode.VIEW_CHARACTERS -> KeyboardMode.CHARACTERS
            KeyCode.VIEW_SYMBOLS -> KeyboardMode.SYMBOLS
            KeyCode.VIEW_SYMBOLS2 -> KeyboardMode.SYMBOLS2
            KeyCode.VIEW_NUMERIC_ADVANCED -> KeyboardMode.NUMERIC_ADVANCED
            else -> null
        }
    }
}
