package id.walt.verify.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.walt.verify.example.ui.theme.VerifyKYCExampleTheme

/**
 * Result screen showing the outcome of the KYC verification flow.
 */
@Composable
fun ResultScreen(
    success: Boolean,
    verifiedData: Map<String, Map<String, String>>,
    onStartOver: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Result icon
        if (success) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = Icons.Default.Cancel,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = if (success) "Verification Complete!" else "Verification Failed",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Subtitle
        Text(
            text = if (success) {
                "Your identity has been successfully verified."
            } else {
                "We were unable to complete your identity verification."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Show verified data on success
        if (success && verifiedData.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Verified Information",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    verifiedData.forEach { (stepId, claims) ->
                        VerifiedStepSection(
                            stepId = stepId,
                            claims = claims
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }

        // Error guidance on failure
        if (!success) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "What you can do:",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    BulletPoint("Check that your wallet app has the required credentials")
                    BulletPoint("Ensure you approve the credential sharing request")
                    BulletPoint("Try scanning the QR code again")
                    BulletPoint("Contact support if the problem persists")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action button
        Button(
            onClick = onStartOver,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = if (success) Icons.Default.Home else Icons.Default.Refresh,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (success) "Done" else "Try Again")
        }
    }
}

@Composable
private fun VerifiedStepSection(
    stepId: String,
    claims: Map<String, String>
) {
    val title = when (stepId) {
        "identity" -> "Identity"
        "document" -> "Document"
        "address" -> "Address"
        else -> stepId.replaceFirstChar { it.uppercase() }
    }

    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(4.dp))

        claims.forEach { (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatClaimName(key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/**
 * Format a claim name for display (e.g., "full_name" -> "Full Name").
 */
private fun formatClaimName(name: String): String {
    return name
        .replace("_", " ")
        .split(" ")
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
}

@Preview(showBackground = true)
@Composable
private fun ResultScreenSuccessPreview() {
    VerifyKYCExampleTheme {
        ResultScreen(
            success = true,
            verifiedData = mapOf(
                "identity" to mapOf(
                    "full_name" to "John Doe",
                    "date_of_birth" to "1990-01-15"
                ),
                "document" to mapOf(
                    "document_number" to "DL123456",
                    "expiry_date" to "2028-01-15"
                )
            ),
            onStartOver = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ResultScreenFailurePreview() {
    VerifyKYCExampleTheme {
        ResultScreen(
            success = false,
            verifiedData = emptyMap(),
            onStartOver = {}
        )
    }
}
