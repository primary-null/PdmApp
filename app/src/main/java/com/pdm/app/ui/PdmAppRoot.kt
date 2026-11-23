package com.pdm.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.pdm.app.ui.home.tastefulMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdm.app.ui.home.HomeScreen
import com.pdm.app.ui.login.LoginScreen
import com.pdm.app.ui.shell.ShellScreen

@Composable
fun PdmAppRoot(fleet: FleetViewModel = viewModel()) {
    val fleetState by fleet.fleet.collectAsStateWithLifecycle()
    val loginState by fleet.login.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().tastefulMotion(fleetState.signedIn, fleetState.motionStyle)) {
        when {
            fleetState.signedIn -> Box(
                if (fleetState.shell != null) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize(),
            ) { HomeScreen(fleet) }
            // A blink of the login form before the cached fleet opens reads as a bug.
            loginState.starting -> Unit
            else -> LoginScreen(fleet)
        }
        fleetState.shell?.takeIf { fleetState.signedIn }?.let { ShellScreen(it, fleet, fleetState.savedSnippets) }
        ThemeQuipToast(fleetState.themeQuip ?: fleetState.notice) { id ->
            fleet.dismissThemeQuip(id)
            fleet.dismissNotice(id)
        }
    }
}
