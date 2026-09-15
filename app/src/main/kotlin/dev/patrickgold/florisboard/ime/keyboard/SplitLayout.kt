/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.keyboard

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The narrowest window on which the split (thumbs) keyboard is offered (issue #362).
 *
 * The medium width breakpoint: a phone held sideways is already past it, which is where two thumbs on
 * a keyboard that stretches across the whole width first becomes a reach. Below it there is nothing to
 * gain — two halves of under 300dp are not a keyboard, they are two rows of slivers — so the quick
 * action is offered but greyed out rather than hidden, the way the language switch is with one language.
 */
val SplitLayoutMinWindowWidth: Dp = 600.dp

/**
 * Whether a keyboard of this mode may be split into two halves for thumb typing.
 *
 * The letter and symbol layouts have a middle that can be moved apart. A number pad or a phone dial is
 * a block whose keys are read as a block; a hole down the middle of it helps nobody.
 */
fun KeyboardMode.isSplittable(): Boolean = when (this) {
    KeyboardMode.CHARACTERS,
    KeyboardMode.SYMBOLS,
    KeyboardMode.SYMBOLS2 -> true
    else -> false
}
