package local.ocrbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import local.ocrbridge.ui.CropScreen
import local.ocrbridge.ui.HomeScreen
import local.ocrbridge.ui.Localized
import local.ocrbridge.ui.PickScreen
import local.ocrbridge.ui.RecognizeScreen
import local.ocrbridge.ui.SettingsScreen
import local.ocrbridge.ui.SlipSplitTheme
import local.ocrbridge.ui.SplitScreen
import local.ocrbridge.ui.SummaryScreen

private const val SLIDE_MS = 320

private typealias Enter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
private typealias Exit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

// Forward moves slide in from the right, going back slides the other way; both fade.
private val forwardIn: Enter = { slideInHorizontally(tween(SLIDE_MS)) { it / 4 } + fadeIn(tween(SLIDE_MS)) }
private val forwardOut: Exit = { slideOutHorizontally(tween(SLIDE_MS)) { -it / 4 } + fadeOut(tween(SLIDE_MS)) }
private val backIn: Enter = { slideInHorizontally(tween(SLIDE_MS)) { -it / 4 } + fadeIn(tween(SLIDE_MS)) }
private val backOut: Exit = { slideOutHorizontally(tween(SLIDE_MS)) { it / 4 } + fadeOut(tween(SLIDE_MS)) }

/** One activity; every screen is a Compose destination. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            Localized(vm.uiLanguage) { SlipSplitTheme { SlipSplitApp(vm) } }
        }
    }
}

@Composable
private fun SlipSplitApp(vm: AppViewModel) {
    val nav = rememberNavController()
    NavHost(
        nav, startDestination = "home",
        enterTransition = forwardIn, exitTransition = forwardOut,
        popEnterTransition = backIn, popExitTransition = backOut,
    ) {
        composable("home") {
            HomeScreen(
                vm,
                onPhoto = { uri ->
                    vm.importPhoto(uri)
                    nav.navigate("crop")
                },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("crop") {
            CropScreen(vm, onBack = { nav.popBackStack() }, onStart = { corners ->
                vm.recognize(corners)
                nav.navigate("recognize")
            })
        }
        composable("recognize") {
            RecognizeScreen(
                vm,
                onCancel = {
                    vm.cancelRecognition()
                    nav.popBackStack()
                },
                onDone = { nav.navigate("split") { popUpTo("home") } },
            )
        }
        // The list grows out of the recognized receipt rather than sliding in.
        composable("split", enterTransition = { fadeIn(tween(420)) + scaleIn(tween(420), initialScale = 0.94f) }) {
            SplitScreen(
                vm,
                onLeave = {
                    vm.finish()
                    nav.popBackStack("home", false)
                },
                onPick = { picking -> nav.navigate(if (picking) "pick/pick" else "pick/view") },
                onDone = { nav.navigate("summary") },
            )
        }
        composable("pick/{mode}") { entry ->
            PickScreen(vm, picking = entry.arguments?.getString("mode") == "pick", onBack = { nav.popBackStack() })
        }
        composable("summary") {
            SummaryScreen(vm, onBack = { nav.popBackStack() }, onFinish = {
                nav.popBackStack("home", false)
                vm.finish()
            })
        }
        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
    }
}
