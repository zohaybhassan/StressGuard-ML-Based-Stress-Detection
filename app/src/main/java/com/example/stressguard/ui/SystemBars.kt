package com.example.stressguard.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-edge, handled once for the whole app.
 *
 * From API 35 the platform hands every app a full-bleed window whether it asked for one or not, so
 * a layout that does not pad itself draws its header underneath the clock. Rather than fixing that
 * only on new devices, this switches the behaviour on everywhere: the window is always full-bleed
 * and the screens always pad, so one code path produces the same result on API 26 and on API 36.
 *
 * @param top the view to hold clear of the status bar, normally the screen's root.
 * @param bottom the view to hold clear of the navigation bar, normally the bottom navigation.
 * @param bottomFollowsKeyboard also lift [bottom] above the keyboard. Only the assistant needs
 *   this, and only because its composer has to stay reachable while typing.
 */
fun Activity.fitSystemBars(
    top: View,
    bottom: View? = null,
    bottomFollowsKeyboard: Boolean = false,
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    top.padTopForSystemBars()
    bottom?.padBottomForSystemBars(bottomFollowsKeyboard)
}

/**
 * The original padding is captured once, before any inset is added, in both of the functions
 * below. The listener fires repeatedly — on rotation, on the keyboard opening — and reading the
 * live padding each time would add the status bar to itself until the content walked off screen.
 *
 * Neither consumes the insets: sibling views further down the tree still need to see them.
 */
private fun View.padTopForSystemBars() {
    val original = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(top = original + bars.top)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

private fun View.padBottomForSystemBars(followKeyboard: Boolean) {
    val original = paddingBottom
    val types = if (followKeyboard) {
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
    } else {
        WindowInsetsCompat.Type.systemBars()
    }

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        // getInsets of a type mask returns the larger of the two per edge, so a visible keyboard
        // wins over the navigation bar it is covering rather than stacking on top of it.
        view.updatePadding(bottom = original + insets.getInsets(types).bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
