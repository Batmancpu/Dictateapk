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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Holding `?123` open and sliding onto a symbol (issue #366).
 *
 * The risk this guards is not the slide — that mechanism already existed — but the undo: a gesture that
 * returns to the letters when the user meant to switch layers takes a working feature away from everyone
 * who only ever taps that key. So every case where the layer must be *left alone* has a test.
 */
class MomentaryLayerTest {

    private fun heldSymbols(pointerId: Int = 0) = MomentaryLayer().apply {
        begin(pointerId, from = KeyboardMode.CHARACTERS, to = KeyboardMode.SYMBOLS)
    }

    @Test
    fun `the four layer keys name their layer`() {
        assertEquals(KeyboardMode.CHARACTERS, MomentaryLayer.modeFor(KeyCode.VIEW_CHARACTERS))
        assertEquals(KeyboardMode.SYMBOLS, MomentaryLayer.modeFor(KeyCode.VIEW_SYMBOLS))
        assertEquals(KeyboardMode.SYMBOLS2, MomentaryLayer.modeFor(KeyCode.VIEW_SYMBOLS2))
        assertEquals(KeyboardMode.NUMERIC_ADVANCED, MomentaryLayer.modeFor(KeyCode.VIEW_NUMERIC_ADVANCED))
    }

    @Test
    fun `nothing else opens a layer`() {
        assertNull(MomentaryLayer.modeFor('a'.code))
        assertNull(MomentaryLayer.modeFor(KeyCode.SHIFT))
        assertNull(MomentaryLayer.modeFor(KeyCode.SPACE))
        assertNull(MomentaryLayer.modeFor(KeyCode.DELETE))
        // The emoji key switches the UI mode, not the keyboard mode, and its panel is a different
        // composable — the finger's touch stream does not carry over into it.
        assertNull(MomentaryLayer.modeFor(KeyCode.IME_UI_MODE_MEDIA))
    }

    @Test
    fun `a fresh instance holds nothing`() {
        val layer = MomentaryLayer()
        assertTrue(layer.isIdle)
        assertFalse(layer.isPending)
        assertFalse(layer.owns(0))
    }

    @Test
    fun `the wait ends only when the asked-for layer arrives`() {
        val layer = heldSymbols()
        assertTrue(layer.isPending)
        // Some other recomposition of the layer that is on its way out does not count.
        layer.onKeyboardSettled(KeyboardMode.CHARACTERS)
        assertTrue(layer.isPending)
        layer.onKeyboardSettled(KeyboardMode.SYMBOLS)
        assertFalse(layer.isPending)
    }

    @Test
    fun `only the finger that opened the layer owns it`() {
        val layer = heldSymbols(pointerId = 1)
        assertTrue(layer.owns(1))
        assertFalse(layer.owns(0))
    }

    @Test
    fun `lifting without having pressed anything else keeps the layer`() {
        // A plain tap on ?123 — the oldest behaviour there is, and it must survive untouched.
        val layer = heldSymbols()
        assertNull(layer.end(landedCode = KeyCode.VIEW_SYMBOLS, wasUninterrupted = true))
        assertTrue(layer.isIdle)
    }

    @Test
    fun `lifting over a symbol returns to where the gesture started`() {
        val layer = heldSymbols()
        assertEquals(KeyboardMode.CHARACTERS, layer.end(landedCode = '€'.code, wasUninterrupted = false))
        assertTrue(layer.isIdle)
    }

    @Test
    fun `lifting over nothing returns just the same`() {
        // Nothing is typed — that is the caller's business — but the letters still come back.
        val layer = heldSymbols()
        assertEquals(KeyboardMode.CHARACTERS, layer.end(landedCode = null, wasUninterrupted = false))
    }

    @Test
    fun `lifting over another layer key leaves that layer standing`() {
        // Sliding from ?123 onto =\< is someone choosing the second symbol layer out loud. Its own up
        // event has already taken the keyboard there; returning to the letters would overrule them.
        val layer = heldSymbols()
        assertNull(layer.end(landedCode = KeyCode.VIEW_SYMBOLS2, wasUninterrupted = false))
    }

    @Test
    fun `a second finger on a symbol counts as an interruption`() {
        // Hold ?123, tap € with the other hand, let go of ?123. The finger never left the layer key, so
        // only "was anything else pressed" can tell this apart from a tap — the same question Shift asks.
        // Note the key it lifts over is ?123 itself: that is why the "landed on a layer key" exception
        // above has to mean a *different* layer, or this case would be swallowed by it.
        val layer = heldSymbols()
        assertEquals(
            KeyboardMode.CHARACTERS,
            layer.end(landedCode = KeyCode.VIEW_SYMBOLS, wasUninterrupted = false),
        )
    }

    @Test
    fun `a cancelled press always goes back`() {
        val layer = heldSymbols()
        assertEquals(KeyboardMode.CHARACTERS, layer.endCancelled())
        assertTrue(layer.isIdle)
    }

    @Test
    fun `ending an idle layer changes nothing`() {
        val layer = MomentaryLayer()
        assertNull(layer.end(landedCode = 'a'.code, wasUninterrupted = false))
        assertNull(layer.endCancelled())
    }

    @Test
    fun `the return mode is the layer the gesture started in, not the letters`() {
        // ABC held open from the symbols: lifting over a letter goes back to the symbols, not to a
        // hard-coded CHARACTERS.
        val layer = MomentaryLayer().apply {
            begin(0, from = KeyboardMode.SYMBOLS, to = KeyboardMode.CHARACTERS)
        }
        assertEquals(KeyboardMode.SYMBOLS, layer.end(landedCode = 'a'.code, wasUninterrupted = false))
    }

    @Test
    fun `clear releases the finger`() {
        val layer = heldSymbols()
        layer.clear()
        assertTrue(layer.isIdle)
        assertFalse(layer.owns(0))
        assertEquals(MomentaryLayer.NO_POINTER, layer.ownerPointerId)
    }
}
