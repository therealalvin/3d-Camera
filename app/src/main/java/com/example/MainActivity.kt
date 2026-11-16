package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.capture.CaptureViewModel
import com.example.ui.CaptureCameraScreen
import com.example.ui.CleanViewer3DScreen
import com.example.ui.ReviewPhotoScreen
import com.example.ui.SimpleGalleryScreen
import com.example.ui.theme.ThreeDCameraTheme
import kotlinx.serialization.Serializable

@Serializable
data object RouteCapture

@Serializable
data object RouteReview

@Serializable
data object RouteGallery

@Serializable
data class RouteViewer(
    val photoId: String? = null,
    val externalUri: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val incomingUri: Uri? = if (intent?.action == Intent.ACTION_VIEW) intent.data else null

        setContent {
            ThreeDCameraTheme {
                ThreeDCameraNavGraph(initialExternalUri = incomingUri)
            }
        }
    }
}

@Composable
private fun ThreeDCameraNavGraph(initialExternalUri: Uri?) {
    val navController = rememberNavController()
    val captureViewModel: CaptureViewModel = viewModel()
    val startDestination: Any = if (initialExternalUri != null) {
        RouteViewer(externalUri = initialExternalUri.toString())
    } else {
        RouteCapture
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable<RouteCapture> {
            CaptureCameraScreen(
                viewModel = captureViewModel,
                onCaptureReadyForReview = {
                    if (navController.currentBackStackEntry?.destination?.hasRoute<RouteCapture>() == true) {
                        navController.navigate(RouteReview) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenGallery = {
                    if (navController.currentBackStackEntry?.destination?.hasRoute<RouteCapture>() == true) {
                        navController.navigate(RouteGallery) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable<RouteReview> {
            ReviewPhotoScreen(
                viewModel = captureViewModel,
                onRetake = {
                    if (navController.currentBackStackEntry?.destination?.hasRoute<RouteReview>() == true) {
                        if (!navController.popBackStack(RouteCapture, inclusive = false)) {
                            navController.navigate(RouteCapture) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                },
                onSaved = { photoId ->
                    navController.navigate(RouteViewer(photoId = photoId)) {
                        popUpTo(RouteReview) { inclusive = true }
                    }
                }
            )
        }

        composable<RouteGallery> {
            SimpleGalleryScreen(
                onBackToCamera = {
                    if (!navController.popBackStack(RouteCapture, inclusive = false)) {
                        navController.navigate(RouteCapture) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onOpenPhoto = { photoId ->
                    navController.navigate(RouteViewer(photoId = photoId))
                },
                onOpenExternalUri = { uri ->
                    navController.navigate(RouteViewer(externalUri = uri.toString()))
                }
            )
        }

        composable<RouteViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<RouteViewer>()
            CleanViewer3DScreen(
                photoId = route.photoId,
                externalUri = route.externalUri,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(RouteCapture) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onOpenGallery = {
                    navController.navigate(RouteGallery)
                }
            )
        }
    }
}
