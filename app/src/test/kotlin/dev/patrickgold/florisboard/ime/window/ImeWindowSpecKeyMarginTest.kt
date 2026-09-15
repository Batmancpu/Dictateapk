/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.shouldBeGreaterThan
import dev.patrickgold.florisboard.shouldBeGreaterThanOrEqualTo
import dev.patrickgold.florisboard.shouldBeLessThanOrEqualTo
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll

/**
 * The key spacing preference scales a base margin of 2dp horizontally and 5dp vertically, so over its
 * old 50-150 % range it moved the gap by a single device pixel and read as doing nothing at all
 * (issue #365). These cover the widened range: that it now reaches far enough to be seen, and that it
 * cannot reach so far that the margin eats the key it is deflated from.
 */
class ImeWindowSpecKeyMarginTest : FunSpec({
    val tolerance = 1e-3f.dp

    // The extremes the slider hands in, its 0 % and 300 % divided by 100. Kept in step with KeyboardScreen.
    val minSpacingFactor = 0f
    val maxSpacingFactor = 3f

    // The share of a key cell the key itself always keeps, the counterpart of the margin cap in
    // ImeWindowSpec: both sides are deflated, so 30 % each leaves 40 %.
    val minKeyShareOfCell = 0.4f

    // A common phone: 1080x2400 at 2.75x, which lands in PHONE_PORTRAIT.
    val phoneInsets = with(Density(2.75f)) { ImeInsets.Root.of(IntRect(0, 0, 1080, 2400)) }

    fun specOf(
        rootInsets: ImeInsets.Root,
        fixedMode: ImeWindowMode.Fixed = ImeWindowMode.Fixed.NORMAL,
        spacingFactor: Float,
        keyboardHeight: Dp? = null,
    ): ImeWindowSpec.Fixed {
        val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)
        val defaultProps = constraints.defaultProps
        return ImeWindowSpec.Fixed(
            fixedMode = fixedMode,
            props = keyboardHeight?.let { defaultProps.copy(keyboardHeight = it) } ?: defaultProps,
            constraints = constraints,
            userPreferredOptions = ImeWindowSpec.UserPreferredOptions(
                keySpacingFactorH = spacingFactor,
                keySpacingFactorV = spacingFactor,
                fontScale = 1f,
            ),
        )
    }

    test("the spacing range moves the gap by more than a device pixel") {
        val noGap = specOf(phoneInsets, spacingFactor = minSpacingFactor)
        val wideGap = specOf(phoneInsets, spacingFactor = maxSpacingFactor)

        assertSoftly {
            // The gap is the margin on both of the two neighbouring keys, so the visible travel is
            // twice what is asserted here.
            (wideGap.keyMarginH - noGap.keyMarginH).shouldBeGreaterThan(3.dp)
            (wideGap.keyMarginV - noGap.keyMarginV).shouldBeGreaterThan(6.dp)
        }
    }

    test("the widest gap still leaves a key on a keyboard shrunk to its minimum height") {
        // The case the cap exists for: the vertical margin is 5dp of a row that is now barely 35dp tall.
        val constraints = ImeWindowConstraints.of(phoneInsets, ImeWindowMode.Fixed.NORMAL)
        val spec = specOf(
            phoneInsets,
            spacingFactor = maxSpacingFactor,
            keyboardHeight = constraints.minKeyboardHeight,
        )
        val rowHeight = spec.calcRowHeight(spec.props.keyboardHeight)

        (rowHeight - spec.keyMarginV * 2).shouldBeGreaterThanOrEqualTo(rowHeight * minKeyShareOfCell, tolerance)
    }

    // Root bounds a keyboard can exist in at all: under the horizontal padding the compact and thumbs
    // modes ask for, the default keyboard width collapses to zero and the margin scaling divides by it.
    val deviceInsets = arbitrary {
        val deviceDensity = Arb.density().bind()
        val widthDp = Arb.float(320f, 1600f, includeNaNs = false).bind()
        val heightDp = Arb.float(320f, 1600f, includeNaNs = false).bind()
        val widthPx = (widthDp * deviceDensity.density).toInt()
        val heightPx = (heightDp * deviceDensity.density).toInt()
        with(deviceDensity) { ImeInsets.Root.of(IntRect(0, 0, widthPx, heightPx)) }
    }

    test("for all device bounds, fixed modes and spacing factors the key keeps its share of the cell") {
        val spacingFactors = Arb.float(minSpacingFactor, maxSpacingFactor, includeNaNs = false)
        checkAll(deviceInsets, Arb.enum<ImeWindowMode.Fixed>(), spacingFactors) { rootInsets, fixedMode, factor ->
            val spec = specOf(rootInsets, fixedMode, spacingFactor = factor)
            // Mirrors how TextKeyboardLayout sizes a key cell before deflating it by the margins.
            val cellWidth = spec.props.keyboardWidth(spec.constraints) / 10
            val cellHeight = spec.calcRowHeight(spec.props.keyboardHeight)

            assertSoftly {
                spec.keyMarginH.shouldBeGreaterThanOrEqualTo(0.dp)
                spec.keyMarginV.shouldBeGreaterThanOrEqualTo(0.dp)
                (spec.keyMarginH * 2).shouldBeLessThanOrEqualTo(cellWidth * (1f - minKeyShareOfCell), tolerance)
                (spec.keyMarginV * 2).shouldBeLessThanOrEqualTo(cellHeight * (1f - minKeyShareOfCell), tolerance)
            }
        }
    }
})
