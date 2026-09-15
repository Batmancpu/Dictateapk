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

package dev.patrickgold.florisboard.ime.window

import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery
import java.util.function.Consumer

/**
 * Asks the platform to blur whatever is behind the keyboard, as far as the active theme's
 * `window { background-blur }` asks for it (issue #378).
 *
 * Like [ImeSystemUi] this takes up no space — it only reaches out to the real window the IME dialog is
 * built on. The radius lives in the stylesheet rather than in a preference so a theme carries its own
 * design: a translucent theme can ask for a blur, an opaque one has no use for it, and the rule is
 * window-mode aware like every other window rule (`window[windowmode=`floating`]`).
 *
 * Three things can say no, and all three have to be followed live rather than read once:
 *  - the platform API is Android 12; below that the theme simply stays translucent without a blur,
 *  - [WindowManager.isCrossWindowBlurEnabled] is false in battery saver, while some system UI is up, and
 *    on devices whose SurfaceFlinger has no background blur at all,
 *  - the window has to be translucent (`android:windowIsTranslucent`), which is set on `FlorisImeTheme`.
 *
 * What the platform blurs is the **window**, not what we paint inside it. Our IME window spans nearly the
 * whole display (measured: frame `[0,89][1080,2340]` on a 1080x2340 device) because floating mode needs a
 * full-size canvas and [ImeRootView] is MATCH_PARENT, while `onComputeInsets` narrows only the touchable
 * region. So the blur is as large as that frame — see the issue for what making it keyboard-shaped costs.
 */
@Composable
fun ImeWindowBlur() {
    if (!AndroidVersion.ATLEAST_API31_S) return
    ImeWindowBlurApi31()
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun ImeWindowBlurApi31() {
    val view = LocalView.current
    val density = LocalDensity.current
    val windowController = LocalWindowController.current

    val windowConfig by windowController.activeWindowConfig.collectAsState()
    val attributes = remember(windowConfig.mode) {
        mapOf(FlorisImeUi.Attr.WindowMode to windowConfig.mode.toString())
    }
    val radiusPx = with(density) {
        rememberSnyggThemeQuery(FlorisImeUi.Window.elementName, attributes).backgroundBlur().roundToPx()
    }

    val windowManager = remember(view) { view.context.getSystemService(WindowManager::class.java) }
    var isBlurAllowed by remember { mutableStateOf(windowManager.isCrossWindowBlurEnabled) }
    DisposableEffect(windowManager) {
        val listener = Consumer<Boolean> { enabled -> isBlurAllowed = enabled }
        windowManager.addCrossWindowBlurEnabledListener(listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }

    LaunchedEffect(radiusPx, isBlurAllowed) {
        view.context.findWindow()?.setBackgroundBlurRadius(if (isBlurAllowed) radiusPx else 0)
    }
    DisposableEffect(view) {
        onDispose { view.context.findWindow()?.setBackgroundBlurRadius(0) }
    }
}
