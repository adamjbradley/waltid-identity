# Implementing a Custom PSP Adapter

This guide explains how to implement a custom PSP (Payment Service Provider) adapter for production use with Payment Wallet Attestation.

## Overview

The PSP adapter is responsible for:
1. Resolving available funding sources for authenticated users
2. Validating funding sources before credential issuance
3. Retrieving funding source details for credential claims

## Interface Definition

```kotlin
package id.walt.issuer.psp

interface PspAdapter {
    /**
     * Resolve the user's funding sources after authentication.
     *
     * @param subject Authenticated user identifier (from authentication flow)
     * @param attestationIssuer Wallet provider identifier (from Wallet Unit Attestation)
     * @return List of funding sources available to the user
     */
    suspend fun resolveFundingSources(
        subject: String,
        attestationIssuer: String? = null
    ): List<FundingSource>

    /**
     * Validate that a funding source is still active and can be issued.
     *
     * @param fundingSourceId The credentialIdentifier of the funding source
     * @return true if valid, false otherwise
     */
    suspend fun validateFundingSource(fundingSourceId: String): Boolean

    /**
     * Get a specific funding source by its identifier.
     *
     * @param fundingSourceId The credentialIdentifier of the funding source
     * @return The funding source if found, null otherwise
     */
    suspend fun getFundingSource(fundingSourceId: String): FundingSource?
}
```

## FundingSource Data Model

```kotlin
@Serializable
data class FundingSource(
    val credentialIdentifier: String,  // Unique ID returned in token response
    val type: FundingSourceType,       // CARD, ACCOUNT, or ANY
    val panLastFour: String? = null,   // Last 4 digits of PAN (cards)
    val iin: String? = null,           // Issuer Identification Number (cards)
    val ibanLastFour: String? = null,  // Last 4 digits of IBAN (accounts)
    val bic: String? = null,           // Bank Identifier Code (accounts)
    val scheme: String? = null,        // visa, mastercard, sepa, etc.
    val currency: String? = null,      // ISO 4217 currency code
    val icon: String? = null,          // URL to card/bank icon
    val aliasId: String? = null        // PSP-specific alias
)

@Serializable
enum class FundingSourceType {
    CARD,
    ACCOUNT,
    ANY
}
```

## Implementation Example

```kotlin
package com.example.psp

import id.walt.issuer.psp.FundingSource
import id.walt.issuer.psp.FundingSourceType
import id.walt.issuer.psp.PspAdapter
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*

class MyBankPspAdapter(
    private val bankApiBaseUrl: String,
    private val apiKey: String
) : PspAdapter {

    private val httpClient = HttpClient {
        // Configure your HTTP client
    }

    override suspend fun resolveFundingSources(
        subject: String,
        attestationIssuer: String?
    ): List<FundingSource> {
        // Call your bank's API to get user's payment instruments
        val response = httpClient.get("$bankApiBaseUrl/users/$subject/payment-methods") {
            header("Authorization", "Bearer $apiKey")
        }

        val paymentMethods: List<BankPaymentMethod> = response.body()

        return paymentMethods.map { method ->
            FundingSource(
                credentialIdentifier = "pwa_${method.id}",
                type = when (method.type) {
                    "CARD" -> FundingSourceType.CARD
                    "ACCOUNT" -> FundingSourceType.ACCOUNT
                    else -> FundingSourceType.ANY
                },
                panLastFour = method.maskedNumber?.takeLast(4),
                iin = method.bin,
                ibanLastFour = method.maskedIban?.takeLast(4),
                bic = method.bic,
                scheme = method.scheme?.lowercase(),
                currency = method.currency,
                icon = method.iconUrl
            )
        }
    }

    override suspend fun validateFundingSource(fundingSourceId: String): Boolean {
        val methodId = fundingSourceId.removePrefix("pwa_")

        return try {
            val response = httpClient.get("$bankApiBaseUrl/payment-methods/$methodId/status") {
                header("Authorization", "Bearer $apiKey")
            }
            val status: PaymentMethodStatus = response.body()
            status.active && !status.blocked
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getFundingSource(fundingSourceId: String): FundingSource? {
        val methodId = fundingSourceId.removePrefix("pwa_")

        return try {
            val response = httpClient.get("$bankApiBaseUrl/payment-methods/$methodId") {
                header("Authorization", "Bearer $apiKey")
            }
            val method: BankPaymentMethod = response.body()

            FundingSource(
                credentialIdentifier = fundingSourceId,
                type = when (method.type) {
                    "CARD" -> FundingSourceType.CARD
                    "ACCOUNT" -> FundingSourceType.ACCOUNT
                    else -> FundingSourceType.ANY
                },
                panLastFour = method.maskedNumber?.takeLast(4),
                iin = method.bin,
                ibanLastFour = method.maskedIban?.takeLast(4),
                bic = method.bic,
                scheme = method.scheme?.lowercase(),
                currency = method.currency,
                icon = method.iconUrl
            )
        } catch (e: Exception) {
            null
        }
    }
}

// Your bank's API response models
@Serializable
data class BankPaymentMethod(
    val id: String,
    val type: String,
    val maskedNumber: String?,
    val bin: String?,
    val maskedIban: String?,
    val bic: String?,
    val scheme: String?,
    val currency: String?,
    val iconUrl: String?
)

@Serializable
data class PaymentMethodStatus(
    val active: Boolean,
    val blocked: Boolean
)
```

## Configuration

### 1. Add Your Adapter to the Classpath

Include your adapter implementation in the issuer-api classpath (e.g., as a JAR dependency).

### 2. Configure the Adapter

Set the fully qualified class name in configuration:

**Via Environment Variable:**
```bash
PWA_PSP_ADAPTER=com.example.psp.MyBankPspAdapter
```

**Via Config File (pwa.conf):**
```hocon
pspAdapterType = "com.example.psp.MyBankPspAdapter"
```

### 3. Provide Constructor Parameters

If your adapter requires constructor parameters (like API URLs, keys), you have two options:

**Option A: No-arg Constructor with Environment Variables**
```kotlin
class MyBankPspAdapter : PspAdapter {
    private val bankApiBaseUrl = System.getenv("BANK_API_URL") ?: "https://api.mybank.com"
    private val apiKey = System.getenv("BANK_API_KEY") ?: throw IllegalStateException("BANK_API_KEY required")
    // ...
}
```

**Option B: Custom Factory (Advanced)**

For more complex initialization, extend `PspAdapterFactory`:

```kotlin
object MyPspAdapterFactory {
    fun create(): PspAdapter {
        val config = loadConfig()
        return MyBankPspAdapter(config.apiUrl, config.apiKey)
    }
}
```

## Best Practices

### 1. Error Handling

Always handle errors gracefully and return empty lists or null rather than throwing exceptions:

```kotlin
override suspend fun resolveFundingSources(
    subject: String,
    attestationIssuer: String?
): List<FundingSource> {
    return try {
        // API call
    } catch (e: Exception) {
        log.error(e) { "Failed to resolve funding sources for $subject" }
        emptyList()
    }
}
```

### 2. Caching

Consider caching funding sources to reduce API calls:

```kotlin
private val cache = ConcurrentHashMap<String, CachedFundingSources>()

override suspend fun resolveFundingSources(
    subject: String,
    attestationIssuer: String?
): List<FundingSource> {
    val cached = cache[subject]
    if (cached != null && !cached.isExpired()) {
        return cached.sources
    }

    val sources = fetchFromApi(subject)
    cache[subject] = CachedFundingSources(sources, Clock.System.now())
    return sources
}
```

### 3. Credential Identifier Format

Use a consistent, unique format for credential identifiers:

```kotlin
// Good: Includes prefix, type, and unique ID
credentialIdentifier = "pwa_card_${cardId}"
credentialIdentifier = "pwa_account_${accountId}"

// Bad: Not unique across types
credentialIdentifier = cardId
```

### 4. Security

- Never include full PANs or IBANs in funding sources
- Validate attestation issuer if you want to restrict which wallet providers can issue
- Use secure API communication (HTTPS, mutual TLS)
- Rotate API keys regularly

## Testing Your Adapter

Create unit tests for your adapter:

```kotlin
class MyBankPspAdapterTest {

    @Test
    fun testResolveFundingSources() = runTest {
        val adapter = MyBankPspAdapter(
            bankApiBaseUrl = "https://test-api.mybank.com",
            apiKey = "test-key"
        )

        val sources = adapter.resolveFundingSources("test-user-123", null)

        assertTrue(sources.isNotEmpty())
        sources.forEach { source ->
            assertNotNull(source.credentialIdentifier)
            assertNotNull(source.type)
        }
    }

    @Test
    fun testValidateFundingSource() = runTest {
        val adapter = MyBankPspAdapter(...)

        val isValid = adapter.validateFundingSource("pwa_card_123")

        // Assert based on your test data
    }
}
```

## Troubleshooting

### Adapter Not Loading

Check the logs for:
```
Failed to instantiate PSP adapter com.example.psp.MyBankPspAdapter, falling back to MockPspAdapter
```

Common causes:
- Class not on classpath
- No no-arg constructor
- Missing required environment variables

### Empty Funding Sources

If `resolveFundingSources` returns empty:
1. Check user authentication is working
2. Verify API connectivity
3. Check logs for API errors
4. Ensure user has active payment methods

### Validation Always Fails

If `validateFundingSource` always returns false:
1. Check the credential identifier format matches
2. Verify API endpoint for status check
3. Check payment method status in bank backend
