# EUDI Integration Technical Notes

This document captures technical findings, debugging insights, and implementation details discovered during EUDI wallet integration.

## Signed vs Unsigned Request Detection

### How the EUDI Wallet Determines Request Type

The EUDI wallet library determines whether an authorization request is signed or unsigned based on URL parameters, **not** Content-Type headers.

**Source:** `DefaultAuthorizationRequestResolver.kt:145-162`

```kotlin
when {
    !requestValue.isNullOrEmpty() -> PassByValue(...)      // has 'request' param → SIGNED
    !requestUriValue.isNullOrEmpty() -> PassByReference(...) // has 'request_uri' param → SIGNED
    else -> notSecured(requestParams)                       // neither → UNSIGNED
}
```

### Implications

1. **Both parameters must be present** in the bootstrap URL:
   - `client_id` - Required
   - `request_uri` - Required for signed requests

2. **If `request_uri` is missing**, the wallet treats it as unsigned, causing:
   ```
   Invalid resolution: InvalidClientIdPrefix(value=X509SanDns cannot be used in unsigned request)
   ```

3. **The X509SanDns client ID prefix** requires a signed request:
   ```kotlin
   // RequestAuthenticator.kt:140-143
   is SupportedClientIdPrefix.X509SanDns -> {
       ensure(request is ReceivedRequest.Signed) {
           invalidPrefix("${clientIdPrefix.prefix()} cannot be used in unsigned request")
       }
   }
   ```

## Content-Type for JAR Signed Requests

### RFC 9101 Requirement

RFC 9101 (JWT-Secured Authorization Request) Section 10.2 mandates:

```
Content-Type: application/oauth-authz-req+jwt
```

### Implementation

**File:** `Verifier2AuthorizationRequestHandler.kt`

```kotlin
val JarContentType: ContentType = ContentType("application", "oauth-authz-req+jwt")

// When responding with signed JWT:
is JWTStringResponse -> call.respondText(formattedSessionResponse.jwt, JarContentType)
```

### Why This Matters

While the EUDI wallet library determines signed/unsigned from URL parameters (not Content-Type), returning the correct Content-Type:
1. Ensures RFC compliance
2. Prevents potential issues with strict clients
3. Enables proper content negotiation

## HEAD Requests in Server Logs

### Observation

Server logs show HEAD requests returning 404:
```
HEAD /verification-session/{id}/request → 404
```

### Root Cause

These HEAD requests are **NOT from the EUDI wallet**. They come from:
1. Web portal health checks
2. Nginx proxy probes
3. Browser prefetch

### Evidence

Analysis of EUDI wallet library source code confirms the wallet uses direct GET requests:

**Source:** `RequestFetcher.kt:163-168`

```kotlin
private suspend fun HttpClient.getJAR(requestUri: URL): Jwt =
    try {
        get(requestUri) { addAcceptContentTypeJwt() }.body()
    } catch (e: ClientRequestException) {
        throw ResolutionError.UnableToFetchRequestObject(e).asException()
    }
```

### Implication

HEAD 404s in logs are a red herring when debugging wallet issues. Focus on GET requests.

## ADB Shell URL Escaping

### Problem

When using ADB to launch the wallet with an openid4vp URL:

```bash
# BROKEN - & is interpreted by shell
adb shell am start -d "openid4vp://authorize?client_id=xxx&request_uri=yyy"
```

The shell interprets `&` as a background operator, truncating the URL at `client_id`.

### Diagnosis

Use `adb shell dumpsys activity activities` to see the actual URL received:
```bash
adb shell dumpsys activity activities | grep -A5 "intent="
```

If only `client_id` appears without `request_uri`, the URL was truncated.

### Solution

Use single quotes to prevent shell interpretation:

```bash
# CORRECT - single quotes protect &
adb shell am start -a android.intent.action.VIEW \
  -d 'openid4vp://authorize?client_id=xxx&request_uri=yyy'
```

Or use the QR code / share method instead of ADB.

## Wallet HTTP Client Configuration

### ContentNegotiation Wrapper

The EUDI wallet wraps its HTTP client with Ktor's ContentNegotiation plugin for JSON:

**Source:** `KtorHttpClientFactoryExtensions.kt`

```kotlin
install(ContentNegotiation) {
    json(Json { ... })
}
```

### JWT Fetching Bypasses Content Negotiation

When fetching the signed authorization request JWT, the wallet uses `.body<String>()`:

```kotlin
get(requestUri) { addAcceptContentTypeJwt() }.body<String>()
```

This returns the raw JWT string without JSON parsing, so ContentNegotiation doesn't interfere.

### Accept Header

The wallet sends:
```
Accept: application/oauth-authz-req+jwt, application/jwt
```

## Custom Docker Image Requirement

### Why Custom Builds Are Needed

The standard walt.id Docker Hub images may not include:
1. EUDI-specific credential configurations
2. Protocol fixes for Draft 13+ compatibility
3. Content-Type fixes for JAR

### Build Process

```bash
# From repository root
./gradlew :waltid-services:waltid-issuer-api:jibDockerBuild
./gradlew :waltid-services:waltid-verifier-api:jibDockerBuild

# Tag to match docker-compose VERSION_TAG (default: stable)
docker tag waltid/issuer-api:latest waltid/issuer-api:stable
docker tag waltid/verifier-api:latest waltid/verifier-api:stable

# Restart services
cd docker-compose
docker compose up -d --force-recreate issuer-api verifier-api
```

### Verification

Check running image:
```bash
docker inspect waltid/issuer-api:stable | jq '.[0].Config.Labels'
```

## Protocol Version Differences

### OpenID4VCI Draft 13+ vs Earlier

| Feature | Draft 13+ | Earlier |
|---------|-----------|---------|
| Config identifier | `credential_configuration_id` | `format` + `doctype` |
| Proof format | `proofs: { jwt: [...] }` | `proof: { jwt: "..." }` |
| Response format | `credentials: [...]` | `credential: "..."` |
| Batch issuance | Native support | Not supported |

### OpenID4VP 1.0 vs Drafts

| Feature | 1.0 | Drafts |
|---------|-----|--------|
| Query language | DCQL | Presentation Definition |
| Response mode | `direct_post`, `dc_api` | `fragment`, `query` |
| Client ID | Prefix-based (`x509_san_dns:`) | Plain string |

## Debugging Tips

### Enable Verbose Logging

**Issuer API:**
```properties
logging.level.id.walt.issuer=DEBUG
logging.level.id.walt.oid4vc=DEBUG
```

**Verifier API:**
```properties
logging.level.id.walt.verifier=DEBUG
logging.level.id.walt.openid4vp=DEBUG
```

### Wallet Logs (Android)

```bash
# Clear and capture fresh logs
adb logcat -c
# ... trigger wallet action ...
adb logcat -d | grep -iE "EUDI|openid4vp|presentation|verifier|resolution"
```

### Network Inspection

Use Charles Proxy or mitmproxy to inspect wallet HTTP traffic (requires SSL pinning bypass on wallet).

### Verify JWT Contents

Decode signed authorization request:
```bash
# Get the JWT from the request endpoint
curl -s "https://verifier2.example.com/verification-session/{id}/request" | \
  cut -d'.' -f2 | base64 -d 2>/dev/null | jq .
```

## Known Issues and Workarounds

### Issue: Session Expires Before Wallet Responds

**Cause:** Default 5-minute session timeout

**Workaround:** Increase timeout in session creation or retry with fresh session

### Issue: Certificate Chain Validation Fails

**Cause:** Intermediate certificates not included

**Workaround:** Ensure full certificate chain in `x5c` header

### Issue: Wallet Shows "Untrusted Verifier"

**Cause:** Verifier certificate not in wallet trust store

**Solution:** See [wallet-trust-store-update.md](wallet-trust-store-update.md)

## References

- [RFC 9101 - JWT-Secured Authorization Request (JAR)](https://www.rfc-editor.org/rfc/rfc9101.html)
- [OpenID4VCI Specification](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html)
- [OpenID4VP Specification](https://openid.net/specs/openid-4-verifiable-presentations-1_0.html)
- [EUDI Wallet Architecture Reference Framework](https://github.com/eu-digital-identity-wallet/eudi-doc-architecture-and-reference-framework)
- [eudi-lib-jvm-openid4vp-kt](https://github.com/eu-digital-identity-wallet/eudi-lib-jvm-openid4vp-kt)
- [eudi-lib-jvm-openid4vci-kt](https://github.com/eu-digital-identity-wallet/eudi-lib-jvm-openid4vci-kt)
