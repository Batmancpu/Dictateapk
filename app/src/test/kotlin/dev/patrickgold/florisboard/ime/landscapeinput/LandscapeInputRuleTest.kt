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

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.window.ImeFormFactor
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * The platform decides the fullscreen input view on orientation alone, so "dynamically show" was in
 * truth "always show" and a tablet or an unfolded foldable lost its screen to one text field
 * (issue #361). These cover the size check that was added on top of it: that a phone held sideways
 * keeps what it has, that anything with room above the keyboard keeps showing the app, and that
 * neither of the two explicit user choices is touched by any of it.
 */
class LandscapeInputRuleTest : FunSpec({
    fun formFactorOf(widthDp: Int, heightDp: Int): ImeFormFactor {
        return ImeFormFactor.of(DpRect(0.dp, 0.dp, widthDp.dp, heightDp.dp))
    }

    // A common phone held sideways: 2400x1080 at 2.75x.
    val phone = formFactorOf(872, 393)

    // The near-square window of an unfolded foldable, the device issue #361 was reported from. Its
    // exact dp decide whether the form factor guess calls it a phone or a tablet, which is precisely
    // why the rule asks the window's height instead of the guess.
    val foldable = formFactorOf(820, 700)

    // A tablet in landscape, and a window at the extra-large breakpoint where the desktop class starts.
    val tablet = formFactorOf(1280, 800)
    val desktop = formFactorOf(1600, 900)

    context("dynamically show") {
        val mode = LandscapeInputUiMode.DYNAMICALLY_SHOW

        test("a phone held sideways keeps the fullscreen input") {
            mode.showsFullscreenInput(phone, platformWouldShow = true) shouldBe true
        }

        test("a window with room above the keyboard keeps showing the app") {
            listOf(foldable, tablet, desktop).forAll { formFactor ->
                mode.showsFullscreenInput(formFactor, platformWouldShow = false) shouldBe false
                mode.showsFullscreenInput(formFactor, platformWouldShow = true) shouldBe false
            }
        }

        test("an app that opted out is never overruled") {
            // IME_FLAG_NO_FULLSCREEN, and portrait, both reach us as a platform answer of false.
            mode.showsFullscreenInput(phone, platformWouldShow = false) shouldBe false
        }

        test("the cut is the window height, whatever its width") {
            checkAll(Arb.int(200..3000), Arb.int(480..2000)) { widthDp, heightDp ->
                mode.showsFullscreenInput(
                    formFactorOf(widthDp, heightDp),
                    platformWouldShow = true,
                ) shouldBe false
            }
            checkAll(Arb.int(200..3000), Arb.int(1..479)) { widthDp, heightDp ->
                mode.showsFullscreenInput(
                    formFactorOf(widthDp, heightDp),
                    platformWouldShow = true,
                ) shouldBe true
            }
        }
    }

    context("the explicit choices") {
        test("never show stays never, even where the platform would") {
            listOf(phone, foldable, tablet, desktop).forAll { formFactor ->
                LandscapeInputUiMode.NEVER_SHOW
                    .showsFullscreenInput(formFactor, platformWouldShow = true) shouldBe false
            }
        }

        test("always show stays always, even against an app's opt-out") {
            listOf(phone, foldable, tablet, desktop).forAll { formFactor ->
                LandscapeInputUiMode.ALWAYS_SHOW
                    .showsFullscreenInput(formFactor, platformWouldShow = false) shouldBe true
            }
        }
    }
})
