package id.walt.verify.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import id.walt.verify.example.ui.theme.VerifyKYCExampleTheme

/**
 * Main activity for the KYC Onboarding demo application.
 *
 * This demonstrates how to integrate the walt.id Verify SDK for identity
 * verification in a multi-step KYC onboarding flow.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VerifyKYCExampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KYCOnboardingApp()
                }
            }
        }
    }
}
