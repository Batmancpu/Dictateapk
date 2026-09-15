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

import dev.patrickgold.florisboard.ime.text.gestures.SWIPE_COMMIT_UNITS
import dev.patrickgold.florisboard.ime.text.gestures.SwipeGesture
import dev.patrickgold.florisboard.ime.text.gestures.swipeCommitDirection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vertical step of the space-bar glide (issue #364).
 *
 * What is worth pinning here is not the division but the property underneath it: the line step is a
 * distance the finger travels, and it must stay that distance whatever the user does to the sideways
 * sensitivity. That is the whole reason this arithmetic exists rather than reusing `relUnitCountY`.
 */
class SpaceGlideTest {

    /** The detector's unit is a quarter of the threshold — see `SwipeGesture.Detector.onTouchMove`. */
    private fun unitDp(thresholdDp: Int) = thresholdDp / 4.0

    private fun lineTravelDp(thresholdDp: Int) =
        SpaceGlide.unitsPerLine(thresholdDp) * unitDp(thresholdDp)

    @Test
    fun `at the stock threshold a line is about the intended travel`() {
        // 32 dp threshold -> 8 dp units -> 3 units -> 24 dp, which is LINE_TRAVEL_DP exactly.
        assertEquals(3, SpaceGlide.unitsPerLine(32))
        assertEquals(SpaceGlide.LINE_TRAVEL_DP, lineTravelDp(32))
    }

    @Test
    fun `the line step keeps its distance across the whole threshold range`() {
        // The slider in Gestures runs 12..72 dp. Whatever it is set to, a line must stay within a third
        // of the intended travel — otherwise turning the sideways step finer would make the cursor bolt
        // up the page, which is the failure this class was written to prevent.
        for (threshold in 12..72) {
            val travel = lineTravelDp(threshold)
            assertTrue(
                travel >= SpaceGlide.LINE_TRAVEL_DP * 2 / 3 && travel <= SpaceGlide.LINE_TRAVEL_DP * 4 / 3,
                "threshold $threshold dp gives a line of $travel dp",
            )
        }
    }

    @Test
    fun `a finer sideways step does not buy a finer line step`() {
        // The point of the conversion: halving the threshold halves the character step and leaves the
        // line step where it was.
        assertEquals(lineTravelDp(32), lineTravelDp(16))
        assertTrue(unitDp(16) < unitDp(32))
    }

    @Test
    fun `a line is never fewer than one unit`() {
        // Zero would divide the travel away entirely and freeze the vertical axis.
        for (threshold in 12..72) {
            assertTrue(SpaceGlide.unitsPerLine(threshold) >= 1, "threshold $threshold dp")
        }
    }

    @Test
    fun `the floor is one, not two`() {
        // A floor of two binds only above 64 dp, and there it forces a 36 dp line — outside the bound the
        // test above pins. It would be doing the opposite of its job in the one place it took effect.
        assertEquals(1, SpaceGlide.unitsPerLine(72))
        assertEquals(SpaceGlide.unitsPerLine(72) * unitDp(72), 18.0)
    }

    @Test
    fun `a nonsensical threshold still yields a usable step`() {
        assertTrue(SpaceGlide.unitsPerLine(0) >= 1)
        assertTrue(SpaceGlide.unitsPerLine(-10) >= 1)
    }

    @Test
    fun `the opening report cannot move a line`() {
        // The first unit of travel is what crossed the threshold; it is the price of starting the glide.
        assertEquals(0, SpaceGlide.lineAt(absUnitCountY = 0, unitsPerLine = 3))
        assertEquals(0, SpaceGlide.lineAt(absUnitCountY = 1, unitsPerLine = 3))
        assertEquals(0, SpaceGlide.lineAt(absUnitCountY = -1, unitsPerLine = 3))
    }

    @Test
    fun `up is negative and down is positive, matching the screen`() {
        assertEquals(-2, SpaceGlide.lineAt(absUnitCountY = -6, unitsPerLine = 3))
        assertEquals(2, SpaceGlide.lineAt(absUnitCountY = 6, unitsPerLine = 3))
    }

    @Test
    fun `the count is symmetric about the start`() {
        // Truncation towards zero, so the same travel up and down crosses at the same distance. An
        // asymmetric rule would make a glide that turns around land a line off where it set out.
        for (units in 0..30) {
            assertEquals(
                SpaceGlide.lineAt(units, 3),
                -SpaceGlide.lineAt(-units, 3),
                "at $units units",
            )
        }
    }

    @Test
    fun `a glide that turns around retraces exactly`() {
        // Travel out to seven lines and back, summing the steps the handler would send. Because each
        // line is read off the total travel rather than accumulated, the sum has to come back to zero.
        val unitsPerLine = SpaceGlide.unitsPerLine(32)
        var line = 0
        var sent = 0
        val path = (0..7 * unitsPerLine).toList() + (7 * unitsPerLine - 1 downTo 0).toList()
        for (units in path) {
            val next = SpaceGlide.lineAt(-units, unitsPerLine) // upwards
            sent += next - line
            line = next
        }
        assertEquals(0, line)
        assertEquals(0, sent)
    }

    @Test
    fun `a direction that is switched off moves nothing`() {
        assertEquals(-3, SpaceGlide.allowedLine(0, -3, upAllowed = true, downAllowed = false))
        assertEquals(0, SpaceGlide.allowedLine(0, 3, upAllowed = true, downAllowed = false))
        assertEquals(0, SpaceGlide.allowedLine(0, -3, upAllowed = false, downAllowed = true))
        assertEquals(3, SpaceGlide.allowedLine(0, 3, upAllowed = false, downAllowed = true))
    }

    @Test
    fun `a refused direction does not let the way back overshoot`() {
        // Up on, down off. Glide three lines up, wander back down to the start, then carry on upwards.
        // The count must stay at -3 through the wandering, or the next real step upwards would be
        // measured from where the finger is instead of where the cursor is.
        val up = true
        val down = false
        var line = SpaceGlide.allowedLine(0, -3, up, down)
        assertEquals(-3, line)
        line = SpaceGlide.allowedLine(line, -1, up, down)
        assertEquals(-3, line, "the cursor did not come back down, so the count must not either")
        line = SpaceGlide.allowedLine(line, 0, up, down)
        assertEquals(-3, line)
        line = SpaceGlide.allowedLine(line, -4, up, down)
        assertEquals(-4, line, "one more line up, not four")
    }

    @Test
    fun `with both directions on the count simply follows`() {
        val line = SpaceGlide.allowedLine(-3, 2, upAllowed = true, downAllowed = true)
        assertEquals(2, line)
    }

    @Test
    fun `a downward swipe on the space bar can reach the commit distance`() {
        // The reason the release path could not carry this on its own: the middle of the space bar sits
        // about 77 dp above the bottom of the screen on a 420 dpi phone, so the finger is braking against
        // the edge by the time it lifts and never measures the 1900 dp/s the detector wants. The travel
        // it *can* cover has to be enough for the mid-gesture rule, or the fix would not help either.
        val unitDp = unitDp(32)
        val reachableUnits = (77.0 / unitDp).toInt()
        assertTrue(
            reachableUnits >= SWIPE_COMMIT_UNITS,
            "only $reachableUnits units fit below the space bar, commit needs $SWIPE_COMMIT_UNITS",
        )
        assertEquals(
            SwipeGesture.Direction.DOWN,
            swipeCommitDirection(absUnitCountX = 0, absUnitCountY = reachableUnits),
        )
    }

    @Test
    fun `a swipe well off vertical still commits, a diagonal does not`() {
        // 30 degrees off vertical is an ordinary thumb; 45 is ambiguous and belongs to neither axis.
        assertEquals(
            SwipeGesture.Direction.DOWN,
            swipeCommitDirection(absUnitCountX = 4, absUnitCountY = 8),
        )
        assertEquals(null, swipeCommitDirection(absUnitCountX = 8, absUnitCountY = 8))
    }

    @Test
    fun `sideways resolves through the same rule as vertical`() {
        // Left and right used to have their own release-only branches reading the detector's eight
        // sectors. All four directions now go through one rule, so a sideways swipe that is ended rather
        // than flicked commits too.
        assertEquals(
            SwipeGesture.Direction.LEFT,
            swipeCommitDirection(absUnitCountX = -SWIPE_COMMIT_UNITS, absUnitCountY = 0),
        )
        assertEquals(
            SwipeGesture.Direction.RIGHT,
            swipeCommitDirection(absUnitCountX = SWIPE_COMMIT_UNITS, absUnitCountY = 0),
        )
        // Just short of the distance is still nothing, whichever way it points.
        assertEquals(null, swipeCommitDirection(absUnitCountX = -(SWIPE_COMMIT_UNITS - 1), absUnitCountY = 0))
    }

    @Test
    fun `on release the direction is read the same way, without a distance of its own`() {
        // The detector has already demanded distance and speed by then, so commitUnits is 1.
        assertEquals(
            SwipeGesture.Direction.DOWN,
            swipeCommitDirection(absUnitCountX = 0, absUnitCountY = 2, commitUnits = 1),
        )
        assertEquals(
            SwipeGesture.Direction.UP,
            swipeCommitDirection(absUnitCountX = 0, absUnitCountY = -2, commitUnits = 1),
        )
    }

    @Test
    fun `every step the handler sends is at least one line`() {
        // The handler turns a non-zero delta into that many arrow presses; a delta it cannot act on would
        // be a lost line, and one larger than a line at a time means the finger outran the sampling.
        val unitsPerLine = SpaceGlide.unitsPerLine(32)
        var line = 0
        for (units in 0..40) {
            val next = SpaceGlide.lineAt(-units, unitsPerLine)
            val delta = next - line
            assertTrue(abs(delta) <= 1, "jumped $delta lines at $units units")
            line = next
        }
        assertEquals(-(40 / unitsPerLine), line)
    }
}
