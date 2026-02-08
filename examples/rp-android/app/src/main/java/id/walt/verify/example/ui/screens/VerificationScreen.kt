package id.walt.verify.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import id.walt.verify.example.KYCViewModel
import id.walt.verify.example.StepState
import id.walt.verify.sdk.VerificationResponse

/**
 * Screen for a single verification step.
 * Shows QR code for wallet scanning and handles polling for results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationScreen(
    viewModel: KYCViewModel,
    stepIndex: Int,
    onStepComplete: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val step = viewModel.verificationSteps.getOrNull(stepIndex) ?: return
    val stepStates by viewModel.stepStates.collectAsState()
    val currentState = stepStates[stepIndex] ?: StepState.Idle

    // Start verification when entering the screen
    LaunchedEffect(stepIndex) {
        if (currentState is StepState.Idle) {
            viewModel.startVerification(stepIndex)
        }
    }

    // Handle success state
    LaunchedEffect(currentState) {
        if (currentState is StepState.Success) {
            onStepComplete(true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Step ${stepIndex + 1} of ${viewModel.verificationSteps.size}") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Step header
            val icon = when (step.icon) {
                "person" -> Icons.Default.Person
                "badge" -> Icons.Default.Badge
                "home" -> Icons.Default.Home
                else -> Icons.Default.VerifiedUser
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = step.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // State-dependent content
            when (val state = currentState) {
                is StepState.Idle, is StepState.Loading -> {
                    LoadingContent()
                }

                is StepState.WaitingForScan -> {
                    QRCodeContent(
                        verification = state.verification,
                        onStartPolling = {
                            viewModel.startPolling(stepIndex, state.verification.sessionId)
                        }
                    )
                }

                is StepState.Polling -> {
                    PollingContent(status = state.status)
                }

                is StepState.Success -> {
                    SuccessContent()
                }

                is StepState.Failed -> {
                    FailedContent(
                        message = state.message,
                        onRetry = { viewModel.retryStep(stepIndex) },
                        onCancel = { onStepComplete(false) }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Progress indicator
            LinearProgressIndicator(
                progress = { (stepIndex + 1).toFloat() / viewModel.verificationSteps.size },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Preparing verification...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QRCodeContent(
    verification: VerificationResponse,
    onStartPolling: () -> Unit
) {
    var pollingStarted by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Generate QR code from the verification data
        val qrBitmap = remember(verification.qrCodeData) {
            generateQRCode(verification.qrCodeData, 280)
        }

        if (qrBitmap != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 4.dp
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Scan with your wallet",
                    modifier = Modifier
                        .size(280.dp)
                        .padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Scan with your wallet app",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Open your digital wallet and scan this QR code to share your credentials",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Start polling button
        if (!pollingStarted) {
            Button(
                onClick = {
                    pollingStarted = true
                    onStartPolling()
                }
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("I've Scanned the Code")
            }
        }
    }
}

@Composable
private fun PollingContent(status: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Waiting for verification...",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Please complete the verification in your wallet app",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = "Status: $status",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun SuccessContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Verification Complete!",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Moving to next step...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FailedContent(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Verification Failed",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }

            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

/**
 * Generate a QR code bitmap from the given data.
 */
private fun generateQRCode(data: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix.get(x, y)) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                )
            }
        }

        bitmap
    } catch (e: Exception) {
        null
    }
}
