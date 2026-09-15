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

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

private const val Squircle = "squircle"

private const val CornerSizeTopStart = "cornerSizeTopStart"
private const val CornerSizeTopEnd = "cornerSizeTopEnd"
private const val CornerSizeBottomEnd = "cornerSizeBottomEnd"
private const val CornerSizeBottomStart = "cornerSizeBottomStart"

private const val DpUnit = "dp"

/**
 * How far a corner reaches along its two edges, measured in radii. A circular corner stops at 1.0; a
 * continuous one keeps going, which is what removes the visible kink where the straight edge meets the
 * curve.
 */
private const val CornerExtent = 1.6f

/**
 * Where the Bézier handles sit along that reach. Chosen so the curve keeps a circular corner's distance
 * from the corner point (0.414 r on the diagonal) while spreading 1.6x further along the edges — the
 * curve is redistributed rather than made rounder, which is exactly the difference one sees.
 */
private const val ControlRatio = 0.155f

/**
 * A rounded rectangle whose corners have continuous curvature instead of a circular arc, the shape
 * Apple's platforms use everywhere and nobody can name. Snygg spells it `squircle(12dp, 12dp, 12dp, 12dp)`.
 *
 * The curve is a single cubic Bézier per corner rather than a true superellipse. A superellipse fitted to
 * the whole rectangle deforms the straight edges of anything that is not square — a space bar would bulge
 * — while a per-corner curve leaves the edges alone, which is also how the real thing is built.
 */
private data class SquircleShape(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(size.toRect())

        val isLtr = layoutDirection == LayoutDirection.Ltr
        val maxReach = min(w, h) / 2f
        fun reach(dp: Dp) = with(density) { (dp.toPx() * CornerExtent).coerceIn(0f, maxReach) }
        var tl = reach(if (isLtr) topStart else topEnd)
        var tr = reach(if (isLtr) topEnd else topStart)
        var br = reach(if (isLtr) bottomEnd else bottomStart)
        var bl = reach(if (isLtr) bottomStart else bottomEnd)

        // Two corners sharing an edge must not overrun it. Scale all four by the tightest edge so the
        // shape stays proportional instead of one corner collapsing on its own.
        var scale = 1f
        fun fitEdge(a: Float, b: Float, length: Float) {
            if (a + b > length) scale = min(scale, length / (a + b))
        }
        fitEdge(tl, tr, w)
        fitEdge(tr, br, h)
        fitEdge(br, bl, w)
        fitEdge(bl, tl, h)
        if (scale < 1f) {
            tl *= scale; tr *= scale; br *= scale; bl *= scale
        }

        val path = Path().apply {
            moveTo(tl, 0f)
            lineTo(w - tr, 0f)
            cubicTo(w - tr * ControlRatio, 0f, w, tr * ControlRatio, w, tr)
            lineTo(w, h - br)
            cubicTo(w, h - br * ControlRatio, w - br * ControlRatio, h, w - br, h)
            lineTo(bl, h)
            cubicTo(bl * ControlRatio, h, 0f, h - bl * ControlRatio, 0f, h - bl)
            lineTo(0f, tl)
            cubicTo(0f, tl * ControlRatio, tl * ControlRatio, 0f, tl, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)

data class SnyggSquircleShapeValue(
    override val topStart: Dp,
    override val topEnd: Dp,
    override val bottomEnd: Dp,
    override val bottomStart: Dp,
    override val shape: Shape = SquircleShape(topStart, topEnd, bottomEnd, bottomStart),
) : SnyggDpShapeValue {
    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            function(Squircle) {
                commaList {
                    +float(id = CornerSizeTopStart, unit = DpUnit)
                    +float(id = CornerSizeTopEnd, unit = DpUnit)
                    +float(id = CornerSizeBottomEnd, unit = DpUnit)
                    +float(id = CornerSizeBottomStart, unit = DpUnit)
                }
            }
        }

        override fun defaultValue() = SnyggSquircleShapeValue(0.dp, 0.dp, 0.dp, 0.dp)

        override fun serialize(v: SnyggValue) = runCatching<String> {
            require(v is SnyggSquircleShapeValue)
            val map = snyggIdToValueMapOf(
                CornerSizeTopStart to v.topStart.value,
                CornerSizeTopEnd to v.topEnd.value,
                CornerSizeBottomEnd to v.bottomEnd.value,
                CornerSizeBottomStart to v.bottomStart.value,
            )
            return@runCatching spec.pack(map)
        }

        override fun deserialize(v: String) = runCatching<SnyggValue> {
            val map = snyggIdToValueMapOf()
            spec.parse(v, map)
            return@runCatching SnyggSquircleShapeValue(
                topStart = map.getFloat(CornerSizeTopStart).dp,
                topEnd = map.getFloat(CornerSizeTopEnd).dp,
                bottomEnd = map.getFloat(CornerSizeBottomEnd).dp,
                bottomStart = map.getFloat(CornerSizeBottomStart).dp,
            )
        }
    }

    override fun encoder() = Companion
}
