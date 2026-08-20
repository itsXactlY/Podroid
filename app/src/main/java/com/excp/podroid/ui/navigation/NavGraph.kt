package com.excp.podroid.ui.navigation

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.activity.compose.LocalActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.excp.podroid.ui.screens.home.HomeScreen
import com.excp.podroid.ui.screens.settings.SettingsScreen
import com.excp.podroid.ui.screens.setup.SetupScreen
import com.excp.podroid.ui.screens.terminal.TerminalScreen
import com.excp.podroid.ui.screens.terminal.TerminalViewModel
import com.excp.podroid.ui.screens.x11.X11Screen

object Routes {
    const val SETUP         = "setup"
    const val HOME          = "home"
    const val TERMINAL      = "terminal"
    const val TERMINAL_X11  = "terminal/x11"
    const val SETTINGS      = "settings"
}

@Composable
fun PodroidNavGraph(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController = rememberNavController(),
    // Non-null when the app was opened from the Deadalus launcher alias: go
    // straight to that route instead of HOME. Ignored while setup is pending,
    // because skipping SETUP would strand a fresh install on a screen it
    // cannot use.
    startRouteOverride: String? = null,
    // Set when the Deadalus icon is tapped while the app is already running.
    // Consumed (reset to null) once acted on, so returning to the app later
    // does not yank the operator back to the terminal.
    routeRequest: androidx.compose.runtime.MutableState<String?>? = null,
) {
    // Read isSetupDone from a Hilt-scoped helper so MainActivity doesn't need
    // a field-injected SettingsRepository just to drive the start destination.
    val isSetupDone by hiltViewModel<NavGraphViewModel>()
        .isSetupDone
        .collectAsStateWithLifecycle(initialValue = null)

    // Scoped to PodroidNavGraph composable — survives all navigation including popUpTo(0)
    val terminalViewModel: TerminalViewModel = hiltViewModel()

    val startDestination = when (isSetupDone) {
        true  -> startRouteOverride ?: Routes.HOME
        false -> Routes.SETUP
        null  -> return
    }

    if (routeRequest != null) {
        val requested = routeRequest.value
        androidx.compose.runtime.LaunchedEffect(requested) {
            if (requested != null) {
                if (navController.currentDestination?.route != requested) {
                    navController.navigate(requested) { launchSingleTop = true }
                }
                routeRequest.value = null
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                windowSizeClass = windowSizeClass,
                onSetupComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                windowSizeClass = windowSizeClass,
                onNavigateToTerminal = {
                    navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.TERMINAL) {
            // Opened from the Deadalus launcher icon: fingerprint, then answer
            // the console login and start the agent. Any failure (cancel, no
            // enrolment, nothing stored) simply leaves the normal prompt.
            if (startRouteOverride == Routes.TERMINAL) {
                DeadalusAutoStart(terminalViewModel)
            }
            TerminalScreen(
                windowSizeClass = windowSizeClass,
                viewModel = terminalViewModel,
                onNavigateBack = {
                    // Only pop if we're not already at HOME to avoid the warning
                    if (navController.currentDestination?.route == Routes.TERMINAL) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onNavigateToX11 = {
                    navController.navigate(Routes.TERMINAL_X11) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.TERMINAL_X11) {
            X11Screen(
                onNavigateBack = {
                    if (!navController.popBackStack(Routes.TERMINAL, inclusive = false)) {
                        navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                    }
                },
                onNavigateToTerminal = {
                    if (!navController.popBackStack(Routes.TERMINAL, inclusive = false)) {
                        navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            val activity = LocalActivity.current
            val onLanguageChanged = remember(activity) {
                { activity?.recreate() ?: Unit }
            }
            SettingsScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.SETTINGS) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onLanguageChanged = onLanguageChanged,
            )
        }
    }
}


/**
 * One-tap path behind the Deadalus icon.
 *
 * First run stores the guest password (encrypted, Keystore-backed); every run
 * after that asks for a fingerprint, answers the console login and starts the
 * agent. Any failure — cancel, no enrolment, nothing stored — simply leaves
 * the normal login prompt, which is the right direction to fail in.
 *
 * The waits are the fragile part and are deliberate: the console is a serial
 * line with no reliable "prompt is ready" signal, so we let getty paint the
 * prompt, answer it, then let the shell settle before running the agent.
 */
@Composable
private fun DeadalusAutoStart(terminalViewModel: TerminalViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val activity = context as? androidx.fragment.app.FragmentActivity
    val askToStore = androidx.compose.runtime.remember { mutableStateOf(false) }
    val started = androidx.compose.runtime.remember { mutableStateOf(false) }

    fun typeLogin(user: String, password: String) {
        scope.launch {
            kotlinx.coroutines.delay(1_200)
            terminalViewModel.typeLine(user)
            kotlinx.coroutines.delay(900)
            terminalViewModel.typeLine(password)
            kotlinx.coroutines.delay(1_800)
            terminalViewModel.typeLine("deadalus")
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (started.value || activity == null) return@LaunchedEffect
        started.value = true

        // Wait for the session rather than assuming one: on a cold start the
        // VM may still be booting when this route appears.
        var waited = 0
        while (!terminalViewModel.hasSession && waited < 30_000) {
            kotlinx.coroutines.delay(250)
            waited += 250
        }
        if (!terminalViewModel.hasSession) return@LaunchedEffect

        if (!com.excp.podroid.security.DeadalusUnlock.hasCredential(activity)) {
            // Only offer to remember it if a fingerprint can actually guard it.
            if (com.excp.podroid.security.DeadalusUnlock.canAuthenticate(activity)) {
                askToStore.value = true
            }
            return@LaunchedEffect
        }

        com.excp.podroid.security.DeadalusUnlock.unlock(
            activity,
            onUnlocked = { user, password -> typeLogin(user, password) },
            onFailed = { /* leave the manual login prompt in place */ },
        )
    }

    if (askToStore.value && activity != null) {
        val user = androidx.compose.runtime.remember { mutableStateOf("root") }
        val pw = androidx.compose.runtime.remember { mutableStateOf("") }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askToStore.value = false },
            title = { androidx.compose.material3.Text("Deadalus one-tap") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        "Store the pod login so this icon can unlock it with your " +
                            "fingerprint. It is encrypted on the device and released " +
                            "only after a successful fingerprint."
                    )
                    androidx.compose.foundation.layout.Spacer(
                        androidx.compose.ui.Modifier.height(androidx.compose.ui.unit.Dp(12f))
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = user.value,
                        onValueChange = { user.value = it },
                        singleLine = true,
                        label = { androidx.compose.material3.Text("User") },
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = pw.value,
                        onValueChange = { pw.value = it },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        label = { androidx.compose.material3.Text("Password") },
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = pw.value.isNotEmpty(),
                    onClick = {
                        com.excp.podroid.security.DeadalusUnlock.storeCredential(
                            activity, user.value.ifBlank { "root" }, pw.value
                        )
                        askToStore.value = false
                        typeLogin(user.value.ifBlank { "root" }, pw.value)
                    },
                ) { androidx.compose.material3.Text("Save and start") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { askToStore.value = false }) {
                    androidx.compose.material3.Text("Not now")
                }
            },
        )
    }
}
