package id.walt.verify.example

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.walt.verify.example.ui.screens.ResultScreen
import id.walt.verify.example.ui.screens.VerificationScreen
import id.walt.verify.example.ui.screens.WelcomeScreen

/**
 * Navigation routes for the KYC onboarding flow.
 */
object Routes {
    const val WELCOME = "welcome"
    const val VERIFICATION = "verification/{stepIndex}"
    const val RESULT = "result/{success}"

    fun verification(stepIndex: Int) = "verification/$stepIndex"
    fun result(success: Boolean) = "result/$success"
}

/**
 * Main composable that sets up navigation for the KYC onboarding flow.
 *
 * Flow:
 * 1. Welcome screen explains the process
 * 2. Multi-step verification (identity, document, liveness)
 * 3. Result screen shows success or failure
 */
@Composable
fun KYCOnboardingApp() {
    val navController = rememberNavController()
    val viewModel: KYCViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onStartVerification = {
                    viewModel.resetFlow()
                    navController.navigate(Routes.verification(0))
                }
            )
        }

        composable(Routes.VERIFICATION) { backStackEntry ->
            val stepIndex = backStackEntry.arguments?.getString("stepIndex")?.toIntOrNull() ?: 0

            VerificationScreen(
                viewModel = viewModel,
                stepIndex = stepIndex,
                onStepComplete = { success ->
                    if (success) {
                        val nextStep = stepIndex + 1
                        if (nextStep < viewModel.verificationSteps.size) {
                            navController.navigate(Routes.verification(nextStep)) {
                                popUpTo(Routes.verification(stepIndex)) { inclusive = true }
                            }
                        } else {
                            navController.navigate(Routes.result(true)) {
                                popUpTo(Routes.WELCOME)
                            }
                        }
                    } else {
                        navController.navigate(Routes.result(false)) {
                            popUpTo(Routes.WELCOME)
                        }
                    }
                },
                onCancel = {
                    navController.popBackStack(Routes.WELCOME, false)
                }
            )
        }

        composable(Routes.RESULT) { backStackEntry ->
            val success = backStackEntry.arguments?.getString("success")?.toBoolean() ?: false

            ResultScreen(
                success = success,
                verifiedData = viewModel.getVerifiedData(),
                onStartOver = {
                    viewModel.resetFlow()
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            )
        }
    }
}
