package com.pdm.app.ui.adaptive

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Compact windows stack content; larger windows use two panes. */
const val OPERATOR_TWO_PANE_MIN_WIDTH_DP = 600

fun isOperatorTwoPane(widthDp: Int): Boolean = widthDp >= OPERATOR_TWO_PANE_MIN_WIDTH_DP

/**
 * Top inset for screen content: the status bar on a phone, the window caption bar in a DeX or
 * desktop freeform window (its close/maximize buttons would otherwise sit over the header).
 * Both are zero or equal when only one exists, so this is a no-op change on a phone.
 */
@Composable
fun Modifier.topChromePadding(): Modifier =
    windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.captionBar))
