/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.florisboard.lib.snygg.value

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SnyggSheenValueTest {
    private val validPairs = listOf(
        "0% 0%" to SnyggSheenValue(0, 0),
        "10% 6%" to SnyggSheenValue(10, 6),
        "100% 100%" to SnyggSheenValue(100, 100),
    )

    @Test
    fun `a sheen survives a round trip`() {
        for ((str, value) in validPairs) {
            assertEquals(str, SnyggSheenValue.serialize(value).getOrThrow())
            assertEquals(value, SnyggSheenValue.deserialize(str).getOrThrow())
        }
    }

    @Test
    fun `nonsense is rejected rather than silently clamped`() {
        for (input in listOf("120% 10%", "10 6", "10%", "-10% 6%", "10%,6%", "", "10% 6% 3%")) {
            assertTrue(SnyggSheenValue.deserialize(input).isFailure, "should not parse: '$input'")
        }
    }

    @Test
    fun `a sheen that lifts and shades nothing reports itself as flat`() {
        assertTrue(SnyggSheenValue(0, 0).isFlat)
        assertFalse(SnyggSheenValue(1, 0).isFlat)
        assertFalse(SnyggSheenValue(0, 1).isFlat)
    }

    /**
     * The whole point of the glass themes is translucent surfaces, so a sheen that quietly made its ends
     * opaque would turn the keyboard into a solid slab at top and bottom while looking fine in a preview
     * rendered on white.
     */
    @Test
    fun `lifting and shading leave alpha alone`() {
        // Compose stores sRGB alpha with 8 bits, so 0.5f is already 0.5019608 before we touch it:
        // compare against the source colour rather than against the literal we asked for.
        val translucentBlack = Color(0f, 0f, 0f, 0.5f)
        val top = SnyggSheenValue(20, 0).topColorOf(translucentBlack)
        assertEquals(translucentBlack.alpha, top.alpha, 1e-4f)
        assertEquals(0.2f, top.red, 1e-2f)

        val translucentWhite = Color(1f, 1f, 1f, 0.3f)
        val bottom = SnyggSheenValue(0, 50).bottomColorOf(translucentWhite)
        assertEquals(translucentWhite.alpha, bottom.alpha, 1e-4f)
        assertEquals(0.5f, bottom.red, 1e-2f)
    }

    @Test
    fun `a flat sheen returns the colour untouched at both ends`() {
        val color = Color(0.4f, 0.6f, 0.8f, 0.7f)
        val sheen = SnyggSheenValue(0, 0)
        assertEquals(color, sheen.topColorOf(color))
        assertEquals(color, sheen.bottomColorOf(color))
    }

    @Test
    fun `a squircle survives a round trip and is a dp shape like the others`() {
        val value = SnyggSquircleShapeValue(10.dp, 10.dp, 16.dp, 16.dp)
        val str = SnyggSquircleShapeValue.serialize(value).getOrThrow()
        assertEquals("squircle(10dp,10dp,16dp,16dp)", str.replace(" ", ""))
        val parsed = SnyggSquircleShapeValue.deserialize(str).getOrThrow()
        assertIs<SnyggDpShapeValue>(parsed)
        assertEquals(10.dp, parsed.topStart)
        assertEquals(16.dp, parsed.bottomEnd)
    }

    @Test
    fun `a squircle does not accept what the other corner shapes accept`() {
        for (input in listOf("squircle(10dp)", "rounded-corner(10dp,10dp,10dp,10dp)", "squircle(10%,10%,10%,10%)")) {
            assertTrue(SnyggSquircleShapeValue.deserialize(input).isFailure, "should not parse: '$input'")
        }
    }
}
