/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import dev.patrickgold.florisboard.ime.keyboard.Key
import dev.patrickgold.florisboard.ime.keyboard.Keyboard
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.isSplittable
import dev.patrickgold.florisboard.ime.popup.PopupMapping
import kotlin.math.abs

class TextKeyboard(
    val arrangement: Array<Array<TextKey>>,
    override val mode: KeyboardMode,
    val extendedPopupMapping: PopupMapping?,
    val extendedPopupMappingDefault: PopupMapping?,
) : Keyboard() {
    val rowCount: Int
        get() = arrangement.size

    val keyCount: Int
        get() = arrangement.sumOf { it.size }

    override fun getKeyForPos(pointerX: Float, pointerY: Float): TextKey? {
        for (key in keys()) {
            if (key.touchBounds.contains(pointerX, pointerY)) {
                return key
            }
        }
        return null
    }

    /**
     * The gap this keyboard actually splits by, which is the requested [splitGap] unless there is
     * nothing to split (a number pad, a phone dial) or the gap is so wide that the two halves would
     * have no width left at all.
     *
     * Public because the caller needs the same number before laying out: the gap belongs to no key, so
     * the reference key width has to be measured against what the halves share, not the whole window.
     */
    fun effectiveSplitGap(keyboardWidth: Float, splitGap: Float): Float = when {
        splitGap.isNaN() || splitGap <= 0.0f || !mode.isSplittable() -> 0.0f
        else -> splitGap.coerceAtMost(keyboardWidth / 2.0f)
    }

    override fun layout(
        keyboardWidth: Float,
        keyboardHeight: Float,
        desiredKey: Key,
        extendTouchBoundariesDownwards: Boolean,
        splitGap: Float,
    ) {
        if (arrangement.isEmpty()) return

        val desiredTouchBounds = desiredKey.touchBounds
        val desiredVisibleBounds = desiredKey.visibleBounds
        if (desiredTouchBounds.isEmpty() || desiredVisibleBounds.isEmpty()) return
        if (keyboardWidth.isNaN() || keyboardHeight.isNaN()) return
        val rowMarginH = abs(desiredTouchBounds.width - desiredVisibleBounds.width)
        val rowMarginV = (keyboardHeight - desiredTouchBounds.height * rowCount.toFloat()) / (rowCount - 1).coerceAtLeast(1).toFloat()
        val gap = effectiveSplitGap(keyboardWidth, splitGap)

        for ((r, row) in rows().withIndex()) {
            val posY = (desiredTouchBounds.height + rowMarginV) * r
            val extendDownwards = extendTouchBoundariesDownwards && r + 1 == arrangement.size
            val seam = if (gap > 0.0f) seamIndexOf(row) else 0
            if (seam <= 0) {
                layoutRowSegment(
                    segment = row.asList(),
                    segmentStart = 0.0f,
                    segmentEnd = keyboardWidth,
                    posY = posY,
                    desiredKey = desiredKey,
                    rowMarginH = rowMarginH,
                    extendTouchBoundariesDownwards = extendDownwards,
                )
            } else {
                val halfWidth = (keyboardWidth - gap) / 2.0f
                layoutRowSegment(
                    segment = row.asList().subList(0, seam),
                    segmentStart = 0.0f,
                    segmentEnd = halfWidth,
                    posY = posY,
                    desiredKey = desiredKey,
                    rowMarginH = rowMarginH,
                    extendTouchBoundariesDownwards = extendDownwards,
                )
                layoutRowSegment(
                    segment = row.asList().subList(seam, row.size),
                    segmentStart = halfWidth + gap,
                    segmentEnd = keyboardWidth,
                    posY = posY,
                    desiredKey = desiredKey,
                    rowMarginH = rowMarginH,
                    extendTouchBoundariesDownwards = extendDownwards,
                )
            }
        }
    }

    /**
     * Lays a horizontal run of keys into the x-range from [segmentStart] to [segmentEnd]. A row that is
     * not split is one such run across the whole keyboard; a split row is two of them, one per half
     * (issue #362), which is why the run and not the row is the unit here.
     *
     * The keys keep the flay grow/shrink behavior they have always had, applied to the width of their
     * own run: each half fills its own side, and the touch bounds of the outermost keys reach to the
     * ends of the run, so no tap is lost to the row margin at an edge. The gap between two runs belongs
     * to neither of them and stays dead.
     */
    private fun layoutRowSegment(
        segment: List<TextKey>,
        segmentStart: Float,
        segmentEnd: Float,
        posY: Float,
        desiredKey: Key,
        rowMarginH: Float,
        extendTouchBoundariesDownwards: Boolean,
    ) {
        if (segment.isEmpty()) return

        val desiredTouchBounds = desiredKey.touchBounds
        val desiredVisibleBounds = desiredKey.visibleBounds
        val availableWidth = (segmentEnd - segmentStart - rowMarginH) / desiredTouchBounds.width
        var requestedWidth = 0.0f
        var shrinkSum = 0.0f
        var growSum = 0.0f
        for (key in segment) {
            requestedWidth += key.flayWidthFactor
            shrinkSum += key.flayShrink
            growSum += key.flayGrow
        }
        if (requestedWidth <= availableWidth) {
            // Requested with is smaller or equal to the available with, so we can grow
            val additionalWidth = availableWidth - requestedWidth
            var posX = segmentStart + rowMarginH / 2.0f
            for ((k, key) in segment.withIndex()) {
                val keyWidth = desiredTouchBounds.width * when (growSum) {
                    0.0f -> when (k) {
                        0, segment.size - 1 -> key.flayWidthFactor + additionalWidth / 2.0f
                        else -> key.flayWidthFactor
                    }
                    else -> key.flayWidthFactor + additionalWidth * (key.flayGrow / growSum)
                }
                key.touchBounds.apply {
                    left = posX
                    top = posY
                    right = posX + keyWidth
                    bottom = posY + desiredTouchBounds.height
                }
                key.visibleBounds.apply {
                    left = key.touchBounds.left + abs(desiredTouchBounds.left - desiredVisibleBounds.left) + when {
                        growSum == 0.0f && k == 0 -> ((additionalWidth / 2.0f) * desiredTouchBounds.width)
                        else -> 0.0f
                    }
                    top = key.touchBounds.top + abs(desiredTouchBounds.top - desiredVisibleBounds.top)
                    right = key.touchBounds.right - abs(desiredTouchBounds.right - desiredVisibleBounds.right) - when {
                        growSum == 0.0f && k == segment.size - 1 -> ((additionalWidth / 2.0f) * desiredTouchBounds.width)
                        else -> 0.0f
                    }
                    bottom = key.touchBounds.bottom - abs(desiredTouchBounds.bottom - desiredVisibleBounds.bottom)
                }
                posX += keyWidth
                // After-adjust touch bounds for the row margin
                key.touchBounds.apply {
                    if (k == 0) {
                        left = segmentStart
                    } else if (k == segment.size - 1) {
                        right = segmentEnd
                    }
                    if (extendTouchBoundariesDownwards) {
                        bottom += height
                    }
                }
            }
        } else {
            // Requested size too big, must shrink.
            val clippingWidth = requestedWidth - availableWidth
            var posX = segmentStart + rowMarginH / 2.0f
            for ((k, key) in segment.withIndex()) {
                val keyWidth = desiredTouchBounds.width * if (key.flayShrink == 0.0f) {
                    key.flayWidthFactor
                } else {
                    key.flayWidthFactor - clippingWidth * (key.flayShrink / shrinkSum)
                }
                key.touchBounds.apply {
                    left = posX
                    top = posY
                    right = posX + keyWidth
                    bottom = posY + desiredTouchBounds.height
                }
                key.visibleBounds.apply {
                    left = key.touchBounds.left + abs(desiredTouchBounds.left - desiredVisibleBounds.left)
                    top = key.touchBounds.top + abs(desiredTouchBounds.top - desiredVisibleBounds.top)
                    right = key.touchBounds.right - abs(desiredTouchBounds.right - desiredVisibleBounds.right)
                    bottom = key.touchBounds.bottom - abs(desiredTouchBounds.bottom - desiredVisibleBounds.bottom)
                }
                posX += keyWidth
                // After-adjust touch bounds for the row margin
                key.touchBounds.apply {
                    if (k == 0) {
                        left = segmentStart
                    } else if (k == segment.size - 1) {
                        right = segmentEnd
                    }
                    if (extendTouchBoundariesDownwards) {
                        bottom += height
                    }
                }
            }
        }
    }

    /**
     * The index of the first key of the right half, or 0 for a row that is not to be split.
     *
     * A key may name the seam itself — that is how the two half spacebars of a split bottom row stay
     * one per half — and otherwise it falls where the accumulated key width first reaches half the row,
     * which is the seam a reader would draw: `qwert|yuiop`, `asdfg|hjkl`, `⇧zxcv|bnm⌫`. Deriving it
     * from the widths rather than from the layout file means every layout splits, including the ones
     * whose rows are not ten keys of equal width, and none of the 79 layout files has to say so.
     */
    private fun seamIndexOf(row: Array<TextKey>): Int {
        val explicitSeam = row.indexOfFirst { it.isSplitSeamStart }
        if (explicitSeam > 0) return explicitSeam
        if (row.size < 2) return 0
        var requestedWidth = 0.0f
        for (key in row) {
            requestedWidth += key.flayWidthFactor
        }
        if (requestedWidth <= 0.0f) return 0
        var accumulatedWidth = 0.0f
        for ((k, key) in row.withIndex()) {
            accumulatedWidth += key.flayWidthFactor
            if (accumulatedWidth >= requestedWidth / 2.0f) {
                return (k + 1).coerceAtMost(row.size - 1)
            }
        }
        return 0
    }

    override fun keys(): Iterator<TextKey> {
        return TextKeyboardIterator(arrangement)
    }

    fun rows(): Iterator<Array<TextKey>> {
        return arrangement.iterator()
    }

    class TextKeyboardIterator internal constructor(
        private val arrangement: Array<Array<TextKey>>
    ) : Iterator<TextKey> {
        private var rowIndex: Int = 0
        private var keyIndex: Int = 0

        override fun hasNext(): Boolean {
            return rowIndex < arrangement.size && keyIndex < arrangement[rowIndex].size
        }

        override fun next(): TextKey {
            val next = arrangement[rowIndex][keyIndex]
            if (keyIndex + 1 == arrangement[rowIndex].size) {
                rowIndex++
                keyIndex = 0
            } else {
                keyIndex++
            }
            return next
        }
    }
}
