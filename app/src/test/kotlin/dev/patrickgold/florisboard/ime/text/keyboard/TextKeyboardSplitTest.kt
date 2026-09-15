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
import dev.patrickgold.florisboard.ime.text.key.KeyType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll

/**
 * The split (thumbs) keyboard is a geometry change and nothing else: one function lays every key out,
 * and both what is drawn and what can be tapped come from it (issue #362). So these cover the geometry
 * — where the seam falls, that each half sits at its own edge, that the gap in the middle belongs to no
 * key — and the invariants the unsplit keyboard has always had, which the split must not break.
 */
class TextKeyboardSplitTest : FunSpec({
    val tolerance = 0.01f

    // A window 1280px wide with a 480px gap: two halves of 400px, the shape a tablet in landscape gets.
    val keyboardWidth = 1280f
    val keyboardHeight = 400f
    val splitGap = 480f
    val halfWidth = (keyboardWidth - splitGap) / 2f

    /**
     * A key with the flay factors [TextKey.compute] would give it in a character layout, which is what
     * the layout pass reads. Letters shrink, the modifiers shrink harder and start wider, the enter and
     * layout-switch keys refuse to shrink at all, and only the space bar grows.
     */
    fun keyOf(code: Int, label: String, type: KeyType = KeyType.CHARACTER): TextKey {
        return TextKey(TextKeyData(type = type, code = code, label = label)).also { key ->
            key.flayWidthFactor = when (code) {
                KeyCode.SHIFT, KeyCode.DELETE, KeyCode.VIEW_SYMBOLS, KeyCode.ENTER -> 1.56f
                else -> 1.00f
            }
            key.flayShrink = when (code) {
                KeyCode.SHIFT, KeyCode.DELETE -> 1.5f
                KeyCode.VIEW_SYMBOLS, KeyCode.ENTER -> 0.0f
                else -> 1.0f
            }
            key.flayGrow = when (code) {
                KeyCode.SPACE -> 1.0f
                else -> 0.0f
            }
        }
    }

    fun lettersOf(letters: String) = letters.map { keyOf(it.code, it.toString()) }

    fun keyboardOf(mode: KeyboardMode, vararg rows: List<TextKey>) = TextKeyboard(
        arrangement = Array(rows.size) { rows[it].toTypedArray() },
        mode = mode,
        extendedPopupMapping = null,
        extendedPopupMappingDefault = null,
    )

    fun qwerty(withSecondSpaceBar: Boolean = true): TextKeyboard {
        val bottomRow = buildList {
            add(keyOf(KeyCode.VIEW_SYMBOLS, "?123", KeyType.SYSTEM_GUI))
            add(keyOf(44, ","))
            add(keyOf(KeyCode.SPACE, "space"))
            if (withSecondSpaceBar) {
                add(keyOf(KeyCode.SPACE, "space").also { it.isSplitSeamStart = true })
            }
            add(keyOf(46, "."))
            add(keyOf(KeyCode.ENTER, "enter", KeyType.ENTER_EDITING))
        }
        return keyboardOf(
            KeyboardMode.CHARACTERS,
            lettersOf("qwertyuiop"),
            lettersOf("asdfghjkl"),
            buildList {
                add(keyOf(KeyCode.SHIFT, "shift", KeyType.MODIFIER))
                addAll(lettersOf("zxcvbnm"))
                add(keyOf(KeyCode.DELETE, "delete", KeyType.ENTER_EDITING))
            },
            bottomRow,
        )
    }

    /**
     * The reference key the layout pass measures against, built the way the keyboard composable builds
     * it: a tenth of what the halves share, minus the key margins.
     */
    fun desiredKeyFor(gap: Float, marginH: Float = 4f, marginV: Float = 10f): TextKey {
        return TextKey(TextKeyData.UNSPECIFIED).also { desiredKey ->
            desiredKey.touchBounds.apply {
                width = (keyboardWidth - gap) / 10f
                height = keyboardHeight / 4f
            }
            desiredKey.visibleBounds.applyFrom(desiredKey.touchBounds).deflateBy(marginH, marginV)
        }
    }

    fun TextKeyboard.layoutWith(gap: Float) {
        // The same two steps the keyboard composable takes: the reference key is measured against the
        // gap the keyboard will actually use, not against the one that was asked for.
        val effectiveGap = effectiveSplitGap(keyboardWidth, gap)
        layout(keyboardWidth, keyboardHeight, desiredKeyFor(effectiveGap), false, effectiveGap)
    }

    context("a keyboard that is not split") {
        test("every row covers the full width, once") {
            val keyboard = qwerty(withSecondSpaceBar = false)
            keyboard.layoutWith(0f)

            keyboard.rows().asSequence().toList().forAll { row ->
                assertSoftly {
                    row.first().touchBounds.left shouldBe 0f
                    row.last().touchBounds.right shouldBe keyboardWidth.plusOrMinus(tolerance)
                    row.toList().zipWithNext().forAll { (left, right) ->
                        left.touchBounds.right shouldBe right.touchBounds.left.plusOrMinus(tolerance)
                    }
                }
            }
        }
    }

    context("a split keyboard") {
        test("the seam falls where a reader would draw it") {
            val keyboard = qwerty()
            keyboard.layoutWith(splitGap)

            // qwert|yuiop, asdfg|hjkl, ⇧zxcv|bnm⌫, and the bottom row between its two space bars.
            val firstOfRightHalf = keyboard.rows().asSequence().map { row ->
                row.first { it.touchBounds.left >= halfWidth }.computedLabelForTest()
            }.toList()
            firstOfRightHalf shouldBe listOf("y", "h", "b", "space")
        }

        test("each half sits at its own edge and fills it") {
            val keyboard = qwerty()
            keyboard.layoutWith(splitGap)

            keyboard.rows().asSequence().toList().forAll { row ->
                val leftHalf = row.filter { it.touchBounds.left < halfWidth }
                val rightHalf = row.filter { it.touchBounds.left >= halfWidth }
                assertSoftly {
                    leftHalf.first().touchBounds.left shouldBe 0f
                    leftHalf.last().touchBounds.right shouldBe halfWidth.plusOrMinus(tolerance)
                    rightHalf.first().touchBounds.left shouldBe (halfWidth + splitGap).plusOrMinus(tolerance)
                    rightHalf.last().touchBounds.right shouldBe keyboardWidth.plusOrMinus(tolerance)
                }
            }
        }

        test("the gap belongs to no key") {
            val keyboard = qwerty()
            keyboard.layoutWith(splitGap)

            val rowHeight = keyboardHeight / 4f
            (0 until 4).toList().forAll { r ->
                val y = rowHeight * r + rowHeight / 2f
                keyboard.getKeyForPos(halfWidth + splitGap / 2f, y).shouldBeNull()
            }
        }

        test("both thumbs get a space bar") {
            val keyboard = qwerty()
            keyboard.layoutWith(splitGap)

            val spaceBars = keyboard.keys().asSequence()
                .filter { (it.data as TextKeyData).code == KeyCode.SPACE }
                .toList()
            assertSoftly {
                spaceBars.size shouldBe 2
                spaceBars.first().touchBounds.right shouldBe halfWidth.plusOrMinus(tolerance)
                spaceBars.last().touchBounds.left shouldBe (halfWidth + splitGap).plusOrMinus(tolerance)
            }
        }

        test("a keyboard with nothing to split ignores the gap") {
            // A number pad is read as a block; a hole down its middle helps nobody, so the gap is
            // dropped and the rows are laid out across the full width.
            val numeric = keyboardOf(KeyboardMode.NUMERIC, lettersOf("123"), lettersOf("456"))
            numeric.layoutWith(splitGap)

            numeric.rows().asSequence().toList().forAll { row ->
                row.first().touchBounds.left shouldBe 0f
                row.last().touchBounds.right shouldBe keyboardWidth.plusOrMinus(tolerance)
            }
        }

        test("no gap can turn a key inside out") {
            checkAll(Arb.float(min = 0f, max = keyboardWidth * 2f)) { gap ->
                val keyboard = qwerty()
                keyboard.layoutWith(gap)

                keyboard.keys().asSequence().toList().forAll { key ->
                    assertSoftly {
                        key.touchBounds.width.shouldBeGreaterThanOrEqualTo(0f)
                        key.touchBounds.left.shouldBeGreaterThanOrEqualTo(0f)
                        key.touchBounds.right.shouldBeLessThanOrEqualTo(keyboardWidth + tolerance)
                    }
                }
            }
        }
    }
})

/**
 * The label the layout pass never needs but a test does — the keys here are built directly, without the
 * evaluator that would normally compute one.
 */
private fun TextKey.computedLabelForTest(): String? = (data as? TextKeyData)?.label
