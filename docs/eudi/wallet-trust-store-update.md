# EUDI Wallet Trust Store Update

## Required Manual Steps

The EUDI wallet needs to be updated to trust the walt.id verifier certificates. The wallet already trusts `verifier2.theaustraliahack.com` but also needs to trust `verifier.theaustraliahack.com` for the legacy verifier-api.

### Step 1: Copy the Verifier Certificate

Copy the legacy verifier certificate to the wallet's raw resources:

```bash
cp docker-compose/verifier-api/keys/verifier.theaustraliahack.com.cert.pem \
   /path/to/eudi-app-android-wallet-ui/resources-logic/src/main/res/raw/verifier_theaustraliahack.pem
```

### Step 2: Update WalletCoreConfigImpl.kt

Add the certificate reference to the trust store configuration in:
`core-logic/src/dev/java/eu/europa/ec/corelogic/config/WalletCoreConfigImpl.kt`

Change:
```kotlin
configureReaderTrustStore(
    context,
    R.raw.pidissuerca02_cz,
    // ... other certificates
    R.raw.verifier_example_com,
    R.raw.verifier2_theaustraliahack
)
```

To:
```kotlin
configureReaderTrustStore(
    context,
    R.raw.pidissuerca02_cz,
    // ... other certificates
    R.raw.verifier_example_com,
    R.raw.verifier2_theaustraliahack,
    R.raw.verifier_theaustraliahack  // Legacy verifier
)
```

### Step 3: Rebuild the Wallet APK

```bash
cd /path/to/eudi-app-android-wallet-ui
./gradlew assembleDev
```

### Step 4: Install on Device

```bash
adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

## Currently Trusted Certificates

| Certificate | Domain | Purpose |
|------------|--------|---------|
| `verifier2_theaustraliahack.pem` | verifier2.theaustraliahack.com | verifier-api2 (modern) |
| `verifier_theaustraliahack.pem` | verifier.theaustraliahack.com | verifier-api (legacy) |

## Verification

After updating, the wallet should:
1. Accept verification requests from both verifier domains
2. Show the verifier name in the presentation request screen
3. Successfully complete the VP presentation flow
