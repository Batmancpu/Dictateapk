/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

/**
 * How far the finger has to travel up or down the space bar before the cursor changes line (issue #364).
 *
 * The horizontal half of the glide has a natural unit: one of the swipe detector's units is one
 * character, and how wide a unit is, is the user's own `swipeDistanceThreshold` knob. The vertical half
 * has no such luck. A line of text is far taller than a character is wide, so counting lines in the same
 * units sends the cursor up the page several lines per centimetre of finger — and shrinking the
 * threshold, which is a reasonable thing to want for the horizontal half, would make it worse in exact
 * proportion.
 *
 * So the vertical step is pinned to a real distance instead and converted into whatever units are
 * currently in force. Turning the threshold down buys a finer character step and leaves the line step
 * where it is, which is what someone asking for either of them actually means.
 */
object SpaceGlide {
    /**
     * Finger travel for one line, in dp. Roughly a line of body text, which is what the gesture is
     * imitating — the cursor should end up where the finger points rather than somewhere it has to be
     * hunted back from. Untested by feel on anything but a phone in portrait; it is one number to turn.
     */
    const val LINE_TRAVEL_DP = 24.0

    /**
     * The detector's units that make up one line, given the user's [swipeDistanceThresholdDp]. Its unit
     * is a quarter of that threshold — see `SwipeGesture.Detector.onTouchMove`.
     *
     * Rounded rather than truncated so a threshold that divides badly errs towards the calmer step. The
     * floor of one exists only to survive a threshold outside the slider's 12..72 dp; it is deliberately
     * not two. A floor of two binds nowhere below 65 dp, and above it forces a 36 dp line — the one part
     * of the range where it would be doing the opposite of its job. Jitter needs no guarding there
     * either, since a single unit is already 16 dp by then.
     */
    fun unitsPerLine(swipeDistanceThresholdDp: Int): Int {
        val unitDp = swipeDistanceThresholdDp / 4.0
        if (unitDp <= 0.0) return 1
        return Math.round(LINE_TRAVEL_DP / unitDp).toInt().coerceAtLeast(1)
    }

    /**
     * Which line the finger stands on, counted from where the glide began. Negative is upwards, matching
     * the screen's y axis.
     *
     * Deliberately computed from the travel so far rather than accumulated from each report: a glide that
     * turns around has to retrace exactly, and a sum of rounded steps does not. It also means the first
     * report cannot move anything, which is the same half-unit of grace the horizontal half gets from its
     * `- 1` on the opening move.
     */
    fun lineAt(absUnitCountY: Int, unitsPerLine: Int): Int {
        if (unitsPerLine <= 0) return 0
        return absUnitCountY / unitsPerLine
    }

    /**
     * The line the cursor may actually move to, given that each direction has its own preference and
     * either may be switched off.
     *
     * A refused move must leave the count where it was, not merely skip the arrow presses. The count is
     * the cursor's position, and letting it follow a finger the cursor did not follow makes the way back
     * overshoot by exactly the refused distance — glide three lines up with the downward half turned off,
     * come back, and the cursor would sail three lines past where it started.
     */
    fun allowedLine(current: Int, target: Int, upAllowed: Boolean, downAllowed: Boolean): Int = when {
        target == current -> current
        target < current -> if (upAllowed) target else current
        else -> if (downAllowed) target else current
    }
}
