/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.landscapeinput

import androidx.window.core.layout.WindowSizeClass
import dev.patrickgold.florisboard.ime.window.ImeFormFactor

/**
 * Whether the system's fullscreen input (extract) view is used for the current landscape window – the
 * layout that replaces the app with one big text field and a *Send* button above the keyboard.
 *
 * [platformWouldShow] is what the platform decided, i.e. the return value of
 * `InputMethodService.onEvaluateFullscreenMode`. It is asked first and can only ever take the view
 * away, never add it, so an app that opts out with `IME_FLAG_NO_FULLSCREEN` keeps winning – except
 * under [LandscapeInputUiMode.ALWAYS_SHOW], which is the user overruling both of us on purpose.
 *
 * The platform's own answer is not enough for [LandscapeInputUiMode.DYNAMICALLY_SHOW], because it is
 * not dynamic at all: AOSP goes fullscreen for *any* landscape window that did not opt out, a rule
 * written when a landscape phone had no room for both the keyboard and a line of text. On a tablet or
 * an unfolded foldable it throws away most of the screen to show a text field the app was already
 * showing (issue #361). So we add the size check the platform never makes.
 */
fun LandscapeInputUiMode.showsFullscreenInput(
    formFactor: ImeFormFactor,
    platformWouldShow: Boolean,
): Boolean {
    return when (this) {
        LandscapeInputUiMode.NEVER_SHOW -> false
        LandscapeInputUiMode.ALWAYS_SHOW -> true
        LandscapeInputUiMode.DYNAMICALLY_SHOW -> platformWouldShow && !formFactor.leavesRoomForEditor
    }
}

/**
 * Whether a window of this shape keeps usable room above the keyboard, which is the entire question
 * the fullscreen input view answers.
 *
 * Height is the whole criterion, and it is deliberately read off the window rather than off the
 * [ImeFormFactor.Type] guess: that guess has no landscape class for the near-square window of an
 * unfolded foldable, so the device this was reported from can come out as a phone or as a tablet
 * depending on its exact dp – but either way it is 700dp tall and has plenty of room.
 *
 * The cut is the medium height breakpoint, 480dp. Below it sits every phone held sideways (≈360–430dp),
 * which keeps the behaviour they have today; at or above it the keyboard takes a well-tuned 170–280dp
 * and what is left is a real view of the app, not a sliver.
 */
private val ImeFormFactor.leavesRoomForEditor: Boolean
    get() = sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
