package com.excp.podroid

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.data.repository.LanguageManager
import com.excp.podroid.ui.navigation.NavGraphViewModel
import com.excp.podroid.ui.navigation.PodroidNavGraph
import com.excp.podroid.ui.theme.PodroidTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
// FragmentActivity, not ComponentActivity: androidx BiometricPrompt
// requires one to host its prompt fragment. FragmentActivity extends
// ComponentActivity, so setContent/enableEdgeToEdge are unaffected.
class MainActivity : FragmentActivity() {

    companion object {
        /** Simple name of the launcher alias declared in AndroidManifest.xml. */
        const val DEADALUS_ALIAS = "DeadalusTerminalAlias"
    }

    /**
     * Route the launcher asked for, or null for a normal start.
     *
     * This is state, not a value read once in onCreate: when the app is already
     * running, tapping the Deadalus icon delivers the intent to the existing
     * task via onNewIntent and onCreate never runs again. Reading the intent
     * only in onCreate made the icon work on a cold start and silently resume
     * whatever screen was last open otherwise.
     */
    private val routeRequest = androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun routeFor(i: android.content.Intent?): String? =
        if (i?.component?.className?.endsWith(DEADALUS_ALIAS) == true)
            com.excp.podroid.ui.navigation.Routes.TERMINAL
        else null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFor(intent)?.let { routeRequest.value = it }
    }

    override fun attachBaseContext(newBase: Context?) {
        val base = newBase ?: return super.attachBaseContext(null)
        val savedLang = LanguageManager.getSavedLanguage(base)
        super.attachBaseContext(LanguageManager.wrapContextForLocale(base, savedLang))
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        routeRequest.value = routeFor(intent)
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val navVm: NavGraphViewModel = hiltViewModel()
            val headlessVm: com.excp.podroid.ui.HeadlessViewModel = hiltViewModel()
            val darkTheme by navVm.darkTheme.collectAsStateWithLifecycle(initialValue = null)
            val dynamicColor by navVm.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = false)
            val headlessActive by headlessVm.active.collectAsStateWithLifecycle()

            // Server mode: near-zero window brightness + keep the screen on so the
            // app stays foreground (the VM is never backgrounded -> never killed).
            androidx.compose.runtime.LaunchedEffect(headlessActive) {
                val lp = window.attributes
                val bars = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                val systemBars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
                if (headlessActive) {
                    lp.screenBrightness = 0.004f
                    window.attributes = lp
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    // The soft keyboard is a separate window above the overlay, so
                    // it stays visible (and interactive) over a black server-mode
                    // screen when entered from the terminal. Clear the focused
                    // editor and dismiss the IME so the screen is truly black.
                    window.currentFocus?.clearFocus()
                    (getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager)
                        ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                    // Go fully immersive: hide the status + navigation bars so no
                    // pixels stay lit (OLED burn-in risk on an always-on server
                    // screen). A swipe reveals them transiently. Matches X11.
                    bars.hide(systemBars)
                    bars.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lp
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    bars.show(systemBars)
                }
            }

            PodroidTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        // Launched from the "Deadalus" icon? An activity-alias
                        // cannot carry intent extras, but it does show up as the
                        // component that started us — so switch on that.
                        PodroidNavGraph(
                            windowSizeClass = windowSizeClass,
                            startRouteOverride = routeFor(intent),
                            routeRequest = routeRequest,
                        )
                        if (headlessActive) {
                            com.excp.podroid.ui.components.HeadlessOverlay(onExit = { headlessVm.disable() })
                        }
                    }
                }
            }
        }
    }
}
