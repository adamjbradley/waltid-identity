# Verifier API2 Keys and Certificates

This directory contains the signing keys and X.509 certificates used by the Verifier API2 for OpenID4VP authentication.

## Files

### verifier2.theaustraliahack.com (Production/Testing)
- `verifier2.theaustraliahack.com.key.pem` - EC P-256 private key
- `verifier2.theaustraliahack.com.cert.pem` - Self-signed X.509 certificate with SAN
- `verifier2.theaustraliahack.com.jwk.json` - Key in JWK format

## Configuration

The verifier-service.conf file references these keys directly. For environment-based configuration, set these in your .env file:

```bash
# Client ID (must match certificate SAN)
VERIFIER2_CLIENT_ID=x509_san_dns:verifier2.theaustraliahack.com

# URL prefix (must match certificate SAN domain)
VERIFIER2_URL_PREFIX=https://verifier2.theaustraliahack.com/verification-session

# URL scheme for wallet (mdoc-openid4vp for EUDI wallet mDoc)
VERIFIER2_URL_HOST=mdoc-openid4vp://authorize

# Signing key (JWK format with private key)
VERIFIER2_SIGNING_KEY={"type":"jwk","jwk":{...}}

# X.509 certificate chain (base64 DER encoded)
VERIFIER2_X5C=MIIB...
```

## Generating New Keys

To generate a new key pair and self-signed certificate:

```bash
# 1. Generate EC P-256 private key
openssl ecparam -name prime256v1 -genkey -noout -out myverifier.key.pem

# 2. Generate self-signed certificate with SAN
openssl req -new -x509 -key myverifier.key.pem -out myverifier.cert.pem \
  -days 365 -subj "/CN=myverifier.example.com" \
  -addext "subjectAltName=DNS:myverifier.example.com"

# 3. Convert private key to JWK format
# Use https://8gwifi.org/jwkfunctions.jsp or similar tool

# 4. Get base64 DER for x5c
openssl x509 -in myverifier.cert.pem -outform DER | base64 | tr -d '\n'
```

## EUDI Wallet Trust Store

For the EUDI Reference Wallet to trust your verifier, you must add the certificate to the wallet's `ReaderTrustStore`. See the wallet's `WalletCoreConfigImpl.kt` for configuration.

## Security Note

These keys are for testing and development purposes. For production:
- Use certificates signed by a trusted CA
- Store private keys securely (e.g., HSM, secrets manager)
- Rotate keys regularly
