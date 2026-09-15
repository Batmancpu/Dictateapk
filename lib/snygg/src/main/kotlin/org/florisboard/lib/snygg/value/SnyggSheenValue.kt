/*
 * Copyright (C) 2026 DevEmperor (Dictate)
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

package org.florisboard.lib.snygg.value

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private const val TopLift = "topLift"
private const val BottomShade = "bottomShade"

/**
 * How much light a surface catches along its top edge and loses along its bottom one, written as two
 * percentages: `10% 6%` lifts the top of the fill a tenth of the way to white and shades the bottom a
 * sixteenth of the way to black.
 *
 * This is deliberately a *modifier on a colour* rather than a gradient with its own colour stops. A
 * stylesheet keeps saying `background: var(--surface)`, so the sheen survives every variable, every
 * selector — and, the reason it is shaped this way, the user's accent: the Dictate and enter keys take
 * their colour from `--primary`, which [org.florisboard.lib.snygg.SnyggTheme.compileFrom] replaces at
 * load time. A literal gradient would have to hardcode a colour there and would fall apart the moment
 * somebody picks a different accent.
 */
data class SnyggSheenValue(val topLift: Int, val bottomShade: Int) : SnyggValue {
    /** True when this sheen would not change the colour at all, so callers can skip the brush. */
    val isFlat: Boolean
        get() = topLift == 0 && bottomShade == 0

    /**
     * The vertical brush this sheen makes of [color]. The untouched colour sits slightly below the
     * middle because a lit surface loses its highlight faster than it gains its shadow.
     */
    fun brush(color: Color): Brush {
        return Brush.verticalGradient(
            0.0f to topColorOf(color),
            0.55f to color,
            1.0f to bottomColorOf(color),
        )
    }

    /** The lit end of the brush. Named so the alpha-preserving behaviour can be asserted directly. */
    fun topColorOf(color: Color): Color = color.lightenBy(topLift / 100f)

    /** The shaded end of the brush. */
    fun bottomColorOf(color: Color): Color = color.darkenBy(bottomShade / 100f)

    override fun encoder() = Companion

    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            spacedList {
                +percentageInt(id = TopLift)
                +percentageInt(id = BottomShade)
            }
        }

        override fun defaultValue() = SnyggSheenValue(0, 0)

        override fun serialize(v: SnyggValue) = runCatching<String> {
            require(v is SnyggSheenValue)
            val map = snyggIdToValueMapOf(
                TopLift to v.topLift,
                BottomShade to v.bottomShade,
            )
            return@runCatching spec.pack(map)
        }

        override fun deserialize(v: String) = runCatching<SnyggValue> {
            val map = snyggIdToValueMapOf()
            spec.parse(v, map)
            return@runCatching SnyggSheenValue(
                topLift = map.getInt(TopLift),
                bottomShade = map.getInt(BottomShade),
            )
        }
    }
}

/** Moves a colour toward white without touching its alpha — a translucent surface stays translucent. */
private fun Color.lightenBy(factor: Float): Color {
    if (factor <= 0f) return this
    return copy(
        red = red + (1f - red) * factor,
        green = green + (1f - green) * factor,
        blue = blue + (1f - blue) * factor,
    )
}

/** Moves a colour toward black without touching its alpha. */
private fun Color.darkenBy(factor: Float): Color {
    if (factor <= 0f) return this
    return copy(
        red = red * (1f - factor),
        green = green * (1f - factor),
        blue = blue * (1f - factor),
    )
}
