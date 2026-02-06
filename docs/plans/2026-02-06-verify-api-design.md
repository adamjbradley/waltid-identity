# Verify API Design Document

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Date:** 2026-02-06
**Status:** Draft
**Author:** Claude Code + Adam Bradley

---

## Executive Summary

Verify API is a multi-tenant SaaS gateway that abstracts the complexity of digital wallet verification for Relying Parties (RPs). It transforms a 6-12 month integration effort into a single API call, handling OID4VP protocol negotiation, credential parsing, and wallet diversity behind a simple REST interface.

**Key Value Propositions:**
- **30-minute integration** vs months of protocol development
- **Template-based verification** for common use cases (age check, KYC, payment auth)
- **Raw credential access** when RPs need full credential data
- **Orchestration support** for multi-step verification flows
- **Multi-tenant SaaS** with logical isolation and usage-based billing

---

## ⚠️ CRITICAL: Feature Flag & Isolation Requirements

### Guiding Principle

**PROTECT EXISTING CODE AT ALL COSTS.** The Verify API is a NEW, ISOLATED service that:

1. **NEVER modifies existing services** - No changes to issuer-api, verifier-api, verifier-api2, wallet-api
2. **NEVER modifies existing libraries** - No changes to waltid-openid4vp, waltid-crypto, etc.
3. **Runs as a SEPARATE container** - Independent deployment, independent failure domain
4. **Disabled by default** - Must be explicitly enabled via environment variable
5. **Zero impact when disabled** - Container doesn't start, no resources consumed

### Feature Flag Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        EXISTING SERVICES (UNCHANGED)                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │ issuer-api  │  │verifier-api │  │verifier-api2│  │ wallet-api  │    │
│  │  (7002)     │  │   (7003)    │  │   (7004)    │  │   (7001)    │    │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘    │
│                         NO MODIFICATIONS ALLOWED                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                     NEW SERVICE (FEATURE-FLAGGED)                        │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                      verify-api (7010)                           │    │
│  │                                                                  │    │
│  │  ONLY STARTS IF: VERIFY_API_ENABLED=true                        │    │
│  │  DEFAULT: Container does not start                               │    │
│  │  PROFILE: verify-api (separate from identity profile)            │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

### Docker Compose Configuration

**File:** `docker-compose/docker-compose.yaml`

```yaml
# NEW SERVICE - Completely separate from existing services
verify-api:
  image: ${IMAGE_PREFIX}waltid/verify-api:${VERSION_TAG:-latest}
  profiles:
    - verify-api    # SEPARATE PROFILE - not part of 'identity' or 'services'
    - all
  pull_policy: missing
  depends_on:
    - postgres
    - valkey
    - verifier-api2
    - caddy
  env_file:
    - .env
    - path: .env.local
      required: false
  environment:
    # Feature flag - container starts but service checks this
    VERIFY_API_ENABLED: ${VERIFY_API_ENABLED:-false}
```

**IMPORTANT:** The `verify-api` profile is SEPARATE from `identity`. Users must explicitly:

```bash
# Existing services (unchanged behavior)
docker compose --profile identity up

# To also enable Verify API
docker compose --profile identity --profile verify-api up

# Or via environment variable
VERIFY_API_ENABLED=true docker compose --profile identity --profile verify-api up
```

### Environment Variable Configuration

**File:** `docker-compose/.env`

```bash
# ============================================
# Verify API Feature Flag
# ============================================
# DISABLED by default - uncomment to enable
# VERIFY_API_ENABLED=true
VERIFY_API_PORT=7010
```

**File:** `docker-compose/.env.local` (gitignored, for local development)

```bash
# Enable Verify API locally
VERIFY_API_ENABLED=true
```

### Service-Level Feature Check

Even if the container starts, the service itself validates the feature flag:

**File:** `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/Main.kt`

```kotlin
fun main() {
    // CRITICAL: Check feature flag before starting
    val enabled = System.getenv("VERIFY_API_ENABLED")?.toBoolean() ?: false

    if (!enabled) {
        println("Verify API is DISABLED. Set VERIFY_API_ENABLED=true to enable.")
        println("Exiting without starting server.")
        return  // Exit cleanly, don't bind port
    }

    println("Verify API is ENABLED. Starting server on port 7010...")
    embeddedServer(Netty, port = 7010, module = Application::module).start(wait = true)
}
```

### Isolation Guarantees

| Guarantee | Implementation |
|-----------|----------------|
| **No existing code modified** | Verify API is a NEW module in `waltid-services/waltid-verify-api/` |
| **No library changes** | Uses existing libraries as dependencies only (read-only) |
| **Separate database tables** | Uses `verify_*` prefixed tables, never touches existing tables |
| **Separate Docker profile** | `--profile verify-api` required to start |
| **Environment flag** | `VERIFY_API_ENABLED=false` by default |
| **Independent failure** | If verify-api crashes, all other services continue working |
| **No shared state** | Only reads from verifier-api2 via HTTP, no shared memory/DB |

### Verification Checklist (MUST PASS)

Before merging any Verify API code:

- [ ] `git diff waltid-services/waltid-issuer-api` shows NO changes
- [ ] `git diff waltid-services/waltid-verifier-api` shows NO changes
- [ ] `git diff waltid-services/waltid-verifier-api2` shows NO changes
- [ ] `git diff waltid-services/waltid-wallet-api` shows NO changes
- [ ] `git diff waltid-libraries` shows NO changes
- [ ] `docker compose --profile identity up` works WITHOUT verify-api
- [ ] All existing tests pass: `./gradlew :waltid-services:waltid-issuer-api:test`
- [ ] All existing tests pass: `./gradlew :waltid-services:waltid-verifier-api2:test`
- [ ] EUDI wallet issuance flow works unchanged
- [ ] EUDI wallet verification flow works unchanged

### Rollback Procedure

If any issues arise:

```bash
# 1. Stop verify-api only (other services continue)
docker compose --profile verify-api down

# 2. Or disable via environment
echo "VERIFY_API_ENABLED=false" >> .env.local
docker compose --profile identity up -d

# 3. Complete removal (if needed)
docker rmi waltid/verify-api:stable
# Verify API is now completely removed, existing services unaffected
```

---

## Problem Statement

### Current State: Direct Wallet Integration

To accept verifiable credentials today, an RP must:

| Requirement | Effort | Expertise |
|-------------|--------|-----------|
| Implement OID4VP protocol | 2-3 months | Protocol specialist |
| Generate signed JAR requests | 2-4 weeks | Cryptography |
| Parse SD-JWT / mDoc credentials | 1-2 months | Credential formats |
| Manage X.509 certificates | Ongoing | PKI operations |
| Handle wallet variations | Ongoing | Mobile ecosystem |
| Build verification UI | 2-4 weeks | Frontend |
| **Total** | **6-12 months** | **3-4 specialists** |

### Target State: Verify API

```bash
curl -X POST https://verify.example.com/v1/verify/identity \
  -H "Authorization: Bearer sk_live_xxx" \
  -d '{"template": "age_check"}'

# Response includes QR code URL - display it, receive webhook when done
```

**Integration time:** 1-2 days with webhooks, 30 minutes with embedded widget.

---

## Architecture Overview

### System Context

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   RP Website    │────▶│   Verify API    │────▶│  User's Wallet  │
│   or Mobile App │◀────│   (This System) │◀────│  (EUDI, etc.)   │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                               │
                               ▼
                        ┌─────────────────┐
                        │ verifier-api2   │
                        │ (waltid-identity│
                        │  existing)      │
                        └─────────────────┘
```

### Component Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Verify API Gateway                          │
├─────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐  │
│  │   Auth &    │  │  Template   │  │   Session   │  │  Webhook   │  │
│  │  Tenants    │  │   Engine    │  │   Manager   │  │ Dispatcher │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  └────────────┘  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐  │
│  │     QR      │  │ Orchestrat- │  │   Billing   │  │   OpenAPI  │  │
│  │  Generator  │  │    ion      │  │   Metering  │  │    Docs    │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  └────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
            ┌─────────────┐                 ┌─────────────┐
            │ PostgreSQL  │                 │   Valkey    │
            │  (Tenants,  │                 │  (Sessions, │
            │  Templates) │                 │   Cache)    │
            └─────────────┘                 └─────────────┘
                                    │
                                    ▼
                        ┌─────────────────────┐
                        │  waltid-verifier-   │
                        │       api2          │
                        │  (OID4VP Engine)    │
                        └─────────────────────┘
```

### Technology Stack

| Component | Technology | Rationale |
|-----------|------------|-----------|
| API Gateway | Kotlin + Ktor | Consistent with waltid-identity |
| Database | PostgreSQL | Existing infrastructure |
| Cache/Sessions | Valkey (Redis) | Fast session lookup, already available |
| Verification | waltid-verifier-api2 | Proven OID4VP implementation |
| QR Generation | zxing library | Standard Java QR library |
| Documentation | OpenAPI 3.1 + Swagger UI | Industry standard |

---

## Data Model

### Tenant Management

```sql
-- Organizations (billing entity)
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    billing_email VARCHAR(255) NOT NULL,
    plan VARCHAR(50) DEFAULT 'free',  -- free, starter, business, enterprise
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- API Keys
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    key_hash VARCHAR(64) NOT NULL,  -- SHA-256 of actual key
    key_prefix VARCHAR(12) NOT NULL,  -- sk_live_abc or sk_test_xyz
    environment VARCHAR(10) NOT NULL,  -- 'test' or 'live'
    name VARCHAR(100),
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    revoked_at TIMESTAMP
);

-- Webhook Endpoints
CREATE TABLE webhooks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    url VARCHAR(2048) NOT NULL,
    secret VARCHAR(64) NOT NULL,
    events VARCHAR(255)[] NOT NULL,  -- array of event types
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);
```

### Templates

```sql
-- Verification Templates
CREATE TABLE templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),  -- NULL for system templates
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(255),
    description TEXT,
    template_type VARCHAR(20) NOT NULL,  -- 'identity' or 'payment'
    dcql_query JSONB NOT NULL,  -- The actual DCQL query
    response_mode VARCHAR(20) DEFAULT 'answers',  -- 'answers' or 'raw_credentials'
    claim_mappings JSONB,  -- Maps credential paths to response field names
    valid_credential_types VARCHAR(255)[],  -- Allowed VCT/doctype values
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(organization_id, name)
);

-- System templates (organization_id = NULL)
INSERT INTO templates (name, display_name, template_type, dcql_query, claim_mappings) VALUES
('age_check', 'Age Verification', 'identity',
 '{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["age_over_18"]}]}]}',
 '{"age_over_18": "is_adult"}'),
('full_kyc', 'Full KYC', 'identity',
 '{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]},{"path":["nationality"]}]}]}',
 '{"family_name":"last_name","given_name":"first_name","birth_date":"date_of_birth","nationality":"nationality"}'),
('transaction_binding', 'Payment Authorization', 'payment',
 '{"credentials":[{"id":"pwa","format":"dc+sd-jwt","meta":{"vct_values":["PaymentWalletAttestation"]},"claims":[{"path":["funding_source"]},{"path":["funding_source","type"]},{"path":["funding_source","panLastFour"]}]}]}',
 '{"funding_source.type":"payment_method","funding_source.panLastFour":"card_last_four"}');
```

### Sessions

```kotlin
// Stored in Valkey with TTL
data class VerificationSession(
    val id: String,                      // vs_abc123
    val organizationId: UUID,
    val templateName: String,
    val responseMode: ResponseMode,      // ANSWERS or RAW_CREDENTIALS
    val status: SessionStatus,           // PENDING, VERIFIED, FAILED, EXPIRED
    val verifierSessionId: String?,      // ID from verifier-api2
    val createdAt: Instant,
    val expiresAt: Instant,
    val result: VerificationResult?,     // Populated on completion
    val metadata: Map<String, String>?   // RP-provided correlation data
)

enum class SessionStatus {
    PENDING,    // Waiting for wallet
    VERIFIED,   // Successfully verified
    FAILED,     // Verification rejected or invalid
    EXPIRED     // Timed out
}

data class VerificationResult(
    val answers: Map<String, Any>?,           // For ANSWERS mode
    val credentials: List<RawCredential>?,    // For RAW_CREDENTIALS mode
    val verifiedAt: Instant,
    val walletInfo: WalletInfo?               // Optional metadata about wallet used
)

data class RawCredential(
    val format: String,           // dc+sd-jwt, mso_mdoc
    val vct: String?,             // For SD-JWT
    val doctype: String?,         // For mDoc
    val raw: String,              // The actual credential
    val disclosedClaims: Map<String, Any>  // Parsed disclosed values
)
```

### Orchestrations

```sql
-- Orchestration Definitions
CREATE TABLE orchestrations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    name VARCHAR(100) NOT NULL,
    steps JSONB NOT NULL,  -- Array of step definitions
    on_complete JSONB,     -- Webhook/redirect config
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(organization_id, name)
);

-- Orchestration Sessions (runtime state in Valkey)
-- Key: orch_session:{id}
-- Value: OrchestrationSession JSON
```

---

## API Specification

### Authentication

All API calls require Bearer token authentication:

```http
Authorization: Bearer sk_live_abc123def456
```

Key format:
- `sk_test_*` - Sandbox environment (mock wallets, no billing)
- `sk_live_*` - Production environment (real wallets, metered)
- `pk_*` - Publishable keys for frontend widget (limited permissions)

### Endpoints

#### Identity Verification

```yaml
POST /v1/verify/identity:
  summary: Start identity verification session
  requestBody:
    content:
      application/json:
        schema:
          type: object
          required: [template]
          properties:
            template:
              type: string
              description: Template name (age_check, full_kyc, or custom)
              example: "age_check"
            response_mode:
              type: string
              enum: [answers, raw_credentials]
              default: answers
              description: Whether to return structured answers or raw credentials
            redirect_uri:
              type: string
              format: uri
              description: Where to redirect user after verification
            metadata:
              type: object
              description: RP-provided data returned in webhook
              example: {"order_id": "ORD-123"}
  responses:
    201:
      description: Verification session created
      content:
        application/json:
          schema:
            type: object
            properties:
              session_id:
                type: string
                example: "vs_abc123"
              qr_code_url:
                type: string
                format: uri
                example: "https://verify.example.com/qr/vs_abc123.png"
              qr_code_data:
                type: string
                description: Raw data for custom QR rendering
              deep_link:
                type: string
                description: Mobile deep link for same-device flow
              expires_at:
                type: string
                format: date-time
```

#### Payment Verification

```yaml
POST /v1/verify/payment:
  summary: Start payment authorization session
  requestBody:
    content:
      application/json:
        schema:
          type: object
          required: [template, transaction]
          properties:
            template:
              type: string
              example: "transaction_binding"
            transaction:
              type: object
              required: [amount, currency, merchant_name]
              properties:
                amount:
                  type: string
                  example: "150.00"
                currency:
                  type: string
                  example: "EUR"
                merchant_name:
                  type: string
                  example: "Example Shop"
                merchant_id:
                  type: string
                reference:
                  type: string
                  description: RP's transaction reference
            metadata:
              type: object
  responses:
    201:
      description: Payment verification session created
      # Same response schema as identity verification
```

#### Session Status

```yaml
GET /v1/sessions/{session_id}:
  summary: Get verification session status
  parameters:
    - name: session_id
      in: path
      required: true
      schema:
        type: string
  responses:
    200:
      description: Session status
      content:
        application/json:
          schema:
            type: object
            properties:
              session_id:
                type: string
              status:
                type: string
                enum: [pending, verified, failed, expired]
              result:
                type: object
                description: Present when status is 'verified'
                properties:
                  answers:
                    type: object
                    description: Present when response_mode is 'answers'
                  credentials:
                    type: array
                    description: Present when response_mode is 'raw_credentials'
                    items:
                      type: object
                      properties:
                        format:
                          type: string
                        vct:
                          type: string
                        raw:
                          type: string
                        disclosed_claims:
                          type: object
              verified_at:
                type: string
                format: date-time
              metadata:
                type: object
```

#### Templates

```yaml
GET /v1/templates:
  summary: List available templates
  responses:
    200:
      description: List of templates
      content:
        application/json:
          schema:
            type: array
            items:
              type: object
              properties:
                name:
                  type: string
                display_name:
                  type: string
                type:
                  type: string
                  enum: [identity, payment]
                description:
                  type: string
                is_system:
                  type: boolean

POST /v1/templates:
  summary: Create custom template
  requestBody:
    content:
      application/json:
        schema:
          type: object
          required: [name, type, claims]
          properties:
            name:
              type: string
              pattern: "^[a-z][a-z0-9_]*$"
            display_name:
              type: string
            type:
              type: string
              enum: [identity, payment]
            claims:
              type: array
              items:
                type: object
                properties:
                  path:
                    type: array
                    items:
                      type: string
                  required:
                    type: boolean
                    default: true
                  alias:
                    type: string
                    description: Field name in response
            valid_credentials:
              type: array
              items:
                type: string
              description: Allowed VCT/doctype values
```

#### Orchestrations

```yaml
POST /v1/orchestrations:
  summary: Create multi-step verification flow
  requestBody:
    content:
      application/json:
        schema:
          type: object
          required: [name, steps]
          properties:
            name:
              type: string
            steps:
              type: array
              items:
                type: object
                required: [id, type, template]
                properties:
                  id:
                    type: string
                    description: Step identifier
                  type:
                    type: string
                    enum: [identity, payment]
                  template:
                    type: string
                  depends_on:
                    type: array
                    items:
                      type: string
                    description: Step IDs that must complete first
                  config:
                    type: object
                    description: Step-specific configuration
            on_complete:
              type: object
              properties:
                webhook:
                  type: string
                  format: uri
                redirect:
                  type: string
                  format: uri

POST /v1/orchestrations/{orchestration_id}/sessions:
  summary: Start orchestrated verification flow
  parameters:
    - name: orchestration_id
      in: path
      required: true
      schema:
        type: string
  requestBody:
    content:
      application/json:
        schema:
          type: object
          properties:
            input:
              type: object
              description: Input data for step variable substitution
            metadata:
              type: object
  responses:
    201:
      description: Orchestration session started
      content:
        application/json:
          schema:
            type: object
            properties:
              orchestration_session_id:
                type: string
              current_step:
                type: string
              verification:
                type: object
                description: Current step's verification session
```

#### Webhooks

```yaml
POST /v1/webhooks:
  summary: Register webhook endpoint
  requestBody:
    content:
      application/json:
        schema:
          type: object
          required: [url, events]
          properties:
            url:
              type: string
              format: uri
            events:
              type: array
              items:
                type: string
                enum:
                  - verification.completed
                  - verification.failed
                  - verification.expired
                  - orchestration.step_completed
                  - orchestration.completed
            secret:
              type: string
              description: Auto-generated if not provided
```

### Webhook Payloads

```json
// verification.completed
{
  "event": "verification.completed",
  "timestamp": "2026-02-06T10:30:00Z",
  "data": {
    "session_id": "vs_abc123",
    "template": "age_check",
    "status": "verified",
    "result": {
      "answers": {
        "is_adult": true
      }
    },
    "metadata": {
      "order_id": "ORD-123"
    }
  }
}

// verification.completed (raw_credentials mode)
{
  "event": "verification.completed",
  "timestamp": "2026-02-06T10:30:00Z",
  "data": {
    "session_id": "vs_abc123",
    "template": "full_kyc",
    "status": "verified",
    "result": {
      "credentials": [
        {
          "format": "dc+sd-jwt",
          "vct": "urn:eudi:pid:1",
          "raw": "eyJ0eXAiOiJ2YytzZC1qd3QiLC...",
          "disclosed_claims": {
            "family_name": "Doe",
            "given_name": "John",
            "birth_date": "1990-01-15"
          }
        }
      ],
      "verification_proof": {
        "timestamp": "2026-02-06T10:30:00Z",
        "verifier_signature": "..."
      }
    },
    "metadata": {}
  }
}

// orchestration.step_completed
{
  "event": "orchestration.step_completed",
  "timestamp": "2026-02-06T10:31:00Z",
  "data": {
    "orchestration_session_id": "orch_xyz789",
    "step_id": "identity_check",
    "step_result": {
      "verified": true,
      "answers": {"is_adult": true}
    },
    "next_step": "payment_auth",
    "overall_status": "in_progress"
  }
}
```

---

## Security Considerations

### API Key Security

- Keys stored as SHA-256 hashes, never plaintext
- Key prefix (`sk_live_`, `sk_test_`) stored for identification
- Keys can be revoked instantly
- Rate limiting per key: 100 req/min (free), 1000 req/min (paid)

### Webhook Security

- All webhooks signed with HMAC-SHA256
- Signature in `X-Verify-Signature` header
- Timestamp in `X-Verify-Timestamp` to prevent replay
- Verification:
  ```python
  expected = hmac.new(secret, f"{timestamp}.{body}".encode(), 'sha256').hexdigest()
  if not hmac.compare_digest(signature, expected):
      raise InvalidSignature()
  if abs(time.time() - int(timestamp)) > 300:  # 5 min tolerance
      raise ReplayAttack()
  ```

### Credential Handling

- Raw credentials only returned if explicitly requested (`response_mode: raw_credentials`)
- Credentials never logged or persisted (only session metadata)
- All credential data encrypted in transit (TLS 1.3)
- Optional: Tenant can configure credential retention policy for audit

### Tenant Isolation

- All database queries include `organization_id` filter
- Session IDs include organization prefix for fast routing
- Valkey keys namespaced by organization
- No cross-tenant data access possible via API

---

## Deployment Architecture

### Container Structure

```yaml
# New services to add to docker-compose.yaml
services:
  verify-api:
    image: waltid/verify-api:${VERSION_TAG}
    depends_on:
      - postgres
      - valkey
      - verifier-api2
    environment:
      DATABASE_URL: postgresql://...
      VALKEY_URL: redis://valkey:6379
      VERIFIER_API2_URL: http://verifier-api2:7004
    ports:
      - "7010:7010"

  verify-portal:
    image: waltid/verify-portal:${VERSION_TAG}
    depends_on:
      - verify-api
    environment:
      VERIFY_API_URL: http://verify-api:7010
    ports:
      - "7011:7011"
```

### Service Ports

| Service | Port | Purpose |
|---------|------|---------|
| verify-api | 7010 | API Gateway |
| verify-portal | 7011 | Developer Portal UI |
| verifier-api2 | 7004 | OID4VP Engine (existing) |

---

## Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| API Latency (p99) | < 200ms | Session creation to QR response |
| Verification Success Rate | > 95% | Completed / Started |
| Webhook Delivery | > 99.9% | Delivered / Sent (with retry) |
| Integration Time | < 2 days | Signup to first production verification |
| Uptime | 99.9% | Service availability |

---

## Future Enhancements (Out of Scope)

- **Mobile SDK** - Native iOS/Android SDKs for embedded verification
- **Credential caching** - Store verified credentials for re-presentation
- **Advanced orchestration** - Conditional branching, parallel steps
- **Compliance reporting** - GDPR audit logs, data retention policies
- **White-label portal** - Custom-branded developer portal per enterprise tenant

---

# Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the Verify API gateway layer on top of existing waltid-identity infrastructure.

**Architecture:** Kotlin/Ktor service with PostgreSQL for tenants/templates, Valkey for sessions, delegating to verifier-api2 for OID4VP.

**Tech Stack:** Kotlin 2.3.0, Ktor 3.3.3, Exposed ORM, kotlinx.serialization, zxing for QR.

---

## Phase 0: Isolation & Feature Flag Infrastructure (MANDATORY FIRST)

> ⚠️ **THIS PHASE MUST BE COMPLETED BEFORE ANY OTHER WORK**
>
> Phase 0 establishes the isolation guarantees that protect existing services.
> DO NOT proceed to Phase 1 until all Phase 0 tasks are verified.

### Task 0.1: Verify existing services are unchanged

**Purpose:** Establish baseline - confirm no existing code will be modified.

**Step 1: Create isolation verification script**

Create `scripts/verify-isolation.sh`:

```bash
#!/bin/bash
# Verify API Isolation Check
# Run this BEFORE and AFTER any Verify API changes

set -e

echo "=== Verify API Isolation Check ==="
echo ""

# Check for modifications to existing services
MODIFIED_SERVICES=$(git diff --name-only HEAD -- \
  waltid-services/waltid-issuer-api \
  waltid-services/waltid-verifier-api \
  waltid-services/waltid-verifier-api2 \
  waltid-services/waltid-wallet-api \
  waltid-libraries \
  2>/dev/null || echo "")

if [ -n "$MODIFIED_SERVICES" ]; then
  echo "❌ ISOLATION VIOLATION: The following protected files were modified:"
  echo "$MODIFIED_SERVICES"
  echo ""
  echo "The Verify API must NOT modify any existing services or libraries."
  exit 1
fi

echo "✅ No existing services modified"
echo ""

# Run existing service tests
echo "Running issuer-api tests..."
./gradlew :waltid-services:waltid-issuer-api:test --quiet || {
  echo "❌ issuer-api tests failed"
  exit 1
}
echo "✅ issuer-api tests pass"

echo "Running verifier-api2 tests..."
./gradlew :waltid-services:waltid-verifier-api2:test --quiet || {
  echo "❌ verifier-api2 tests failed"
  exit 1
}
echo "✅ verifier-api2 tests pass"

echo ""
echo "=== Isolation Verified ==="
```

**Step 2: Run isolation check**

```bash
chmod +x scripts/verify-isolation.sh
./scripts/verify-isolation.sh
```

**Step 3: Commit**

```bash
git add scripts/verify-isolation.sh
git commit -m "chore: add isolation verification script for Verify API"
```

---

### Task 0.2: Create separate Docker profile

**Files:**
- Modify: `docker-compose/docker-compose.yaml` (ADD new service, don't modify existing)
- Modify: `docker-compose/.env` (ADD new variables only)
- Modify: `docker-compose/Caddyfile` (ADD new port only)

**Step 1: Add verify-api service to docker-compose.yaml**

Add this NEW service block (do NOT modify any existing services):

```yaml
# ==================================================
# VERIFY API - NEW FEATURE-FLAGGED SERVICE
# ==================================================
# This service is COMPLETELY SEPARATE from existing services.
# It requires explicit opt-in via --profile verify-api
# ==================================================
verify-api:
  image: ${IMAGE_PREFIX}waltid/verify-api:${VERSION_TAG:-latest}
  profiles:
    - verify-api    # SEPARATE PROFILE - not part of 'identity' or 'services'
    - all
  pull_policy: missing
  depends_on:
    postgres:
      condition: service_healthy
    valkey:
      condition: service_healthy
    verifier-api2:
      condition: service_started
    caddy:
      condition: service_started
  env_file:
    - .env
    - path: .env.local
      required: false
  environment:
    VERIFY_API_ENABLED: ${VERIFY_API_ENABLED:-false}
    DATABASE_URL: "jdbc:postgresql://postgres:5432/${DB_NAME}"
    DATABASE_USER: "${DB_USERNAME}"
    DATABASE_PASSWORD: "${DB_PASSWORD}"
    VALKEY_URL: "redis://valkey:6379"
    VERIFIER_API2_URL: "http://verifier-api2:7004"
    PUBLIC_BASE_URL: "${VERIFY_API_EXTERNAL_URL:-http://$SERVICE_HOST:$VERIFY_API_PORT}"
  volumes:
    - ./verify-api/config:/waltid-verify-api/config
```

**Step 2: Add environment variables to .env**

Append to `.env` (do NOT modify existing variables):

```bash
# ==================================================
# Verify API (NEW - Feature Flagged)
# ==================================================
# DISABLED by default - must explicitly enable
# VERIFY_API_ENABLED=true
VERIFY_API_PORT=7010
VERIFY_API_EXTERNAL_URL=http://localhost:7010
```

**Step 3: Add port to Caddyfile**

Add new port block (do NOT modify existing blocks):

```
# Verify API (Feature-flagged, port 7010)
:{$VERIFY_API_PORT:7010} {
    reverse_proxy verify-api:{$VERIFY_API_PORT:7010}
}
```

**Step 4: Verify existing profile still works**

```bash
cd docker-compose
docker compose --profile identity up -d
# Verify all existing services start
docker compose ps
# verify-api should NOT be running
```

**Step 5: Commit**

```bash
git add docker-compose/docker-compose.yaml docker-compose/.env docker-compose/Caddyfile
git commit -m "feat(verify-api): add Docker profile (disabled by default)"
```

---

### Task 0.3: Create empty module with feature flag check

**Files:**
- Create: `waltid-services/waltid-verify-api/build.gradle.kts`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/Main.kt`
- Modify: `settings.gradle.kts` (ADD include only)

**Step 1: Create minimal build.gradle.kts**

```kotlin
plugins {
    kotlin("jvm")
    id("io.ktor.plugin") version "3.0.3"
}

application {
    mainClass.set("id.walt.verifyapi.MainKt")
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
}
```

**Step 2: Create Main.kt with feature flag guard**

```kotlin
package id.walt.verifyapi

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    // ============================================================
    // CRITICAL: Feature flag check - service exits if not enabled
    // ============================================================
    val enabled = System.getenv("VERIFY_API_ENABLED")?.toBoolean() ?: false

    if (!enabled) {
        println("╔════════════════════════════════════════════════════════════╗")
        println("║  Verify API is DISABLED                                    ║")
        println("║                                                            ║")
        println("║  To enable, set environment variable:                      ║")
        println("║    VERIFY_API_ENABLED=true                                 ║")
        println("║                                                            ║")
        println("║  This service will NOT start until explicitly enabled.    ║")
        println("╚════════════════════════════════════════════════════════════╝")
        return  // Exit cleanly without binding port
    }

    println("╔════════════════════════════════════════════════════════════╗")
    println("║  Verify API is ENABLED                                     ║")
    println("║  Starting server on port 7010...                           ║")
    println("╚════════════════════════════════════════════════════════════╝")

    embeddedServer(Netty, port = 7010) {
        routing {
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain)
            }
            get("/") {
                call.respondText(
                    "Verify API - Feature flagged service\n" +
                    "Status: ENABLED\n" +
                    "Docs: /docs",
                    ContentType.Text.Plain
                )
            }
        }
    }.start(wait = true)
}
```

**Step 3: Add to settings.gradle.kts**

Add this line (do NOT modify existing includes):

```kotlin
include(":waltid-services:waltid-verify-api")
```

**Step 4: Build and verify feature flag works**

```bash
# Build
./gradlew :waltid-services:waltid-verify-api:build

# Test feature flag OFF (should exit immediately)
cd waltid-services/waltid-verify-api
java -jar build/libs/waltid-verify-api-*.jar
# Should print "DISABLED" and exit

# Test feature flag ON
VERIFY_API_ENABLED=true java -jar build/libs/waltid-verify-api-*.jar &
curl http://localhost:7010/health
# Should return "OK"
kill %1
```

**Step 5: Run isolation check**

```bash
./scripts/verify-isolation.sh
```

**Step 6: Commit**

```bash
git add waltid-services/waltid-verify-api settings.gradle.kts
git commit -m "feat(verify-api): create module with feature flag guard"
```

---

### Task 0.4: Create verify-api config directory

**Files:**
- Create: `docker-compose/verify-api/config/application.conf`

**Step 1: Create config directory and files**

```bash
mkdir -p docker-compose/verify-api/config
```

**Step 2: Create application.conf**

```hocon
# Verify API Configuration
# This service is feature-flagged and DISABLED by default

ktor {
    deployment {
        port = 7010
    }
}

database {
    url = ${?DATABASE_URL}
    user = ${?DATABASE_USER}
    password = ${?DATABASE_PASSWORD}
}

valkey {
    url = ${?VALKEY_URL}
}

verifier {
    api2Url = ${?VERIFIER_API2_URL}
}

# Public URL for QR codes and callbacks
publicBaseUrl = ${?PUBLIC_BASE_URL}
```

**Step 3: Commit**

```bash
git add docker-compose/verify-api/
git commit -m "feat(verify-api): add config directory"
```

---

### Phase 0 Completion Checklist

Before proceeding to Phase 1, verify:

- [ ] `./scripts/verify-isolation.sh` passes
- [ ] `docker compose --profile identity up` works WITHOUT verify-api starting
- [ ] `docker compose --profile verify-api up` starts verify-api container
- [ ] Container with `VERIFY_API_ENABLED=false` exits cleanly
- [ ] Container with `VERIFY_API_ENABLED=true` starts and responds on :7010/health
- [ ] `git diff waltid-services/waltid-issuer-api` shows NO changes
- [ ] `git diff waltid-libraries` shows NO changes

**Only proceed to Phase 1 after ALL checks pass.**

---

## Phase 1: Project Setup & Core Infrastructure

### Task 1.1: Create verify-api module structure

**Files:**
- Create: `waltid-services/waltid-verify-api/build.gradle.kts`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/Main.kt`
- Modify: `settings.gradle.kts` (add module include)

**Step 1: Create build.gradle.kts**

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin") version "3.0.3"
    id("com.google.cloud.tools.jib")
}

application {
    mainClass.set("id.walt.verifyapi.MainKt")
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-swagger")

    // Ktor client (for verifier-api2 calls)
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:0.44.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.44.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.44.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.44.0")
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Redis/Valkey
    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.google.zxing:javase:3.5.2")

    // Shared libraries
    implementation(project(":waltid-libraries:waltid-crypto"))

    // Testing
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test"))
}
```

**Step 2: Create Main.kt**

```kotlin
package id.walt.verifyapi

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    embeddedServer(Netty, port = 7010, module = Application::module).start(wait = true)
}

fun Application.module() {
    configureDatabase()
    configureSerialization()
    configureAuthentication()
    configureRouting()
    configureOpenAPI()
}
```

**Step 3: Add to settings.gradle.kts**

Add line: `include(":waltid-services:waltid-verify-api")`

**Step 4: Run build to verify**

```bash
./gradlew :waltid-services:waltid-verify-api:build
```

**Step 5: Commit**

```bash
git add waltid-services/waltid-verify-api settings.gradle.kts
git commit -m "feat(verify-api): create module structure"
```

---

### Task 1.2: Database schema and migrations

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/db/Tables.kt`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/db/DatabaseConfig.kt`
- Create: `waltid-services/waltid-verify-api/src/main/resources/db/migration/V1__initial_schema.sql`

**Step 1: Create Exposed table definitions**

```kotlin
// Tables.kt
package id.walt.verifyapi.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Organizations : UUIDTable("organizations") {
    val name = varchar("name", 255)
    val billingEmail = varchar("billing_email", 255)
    val plan = varchar("plan", 50).default("free")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object ApiKeys : UUIDTable("api_keys") {
    val organizationId = reference("organization_id", Organizations)
    val keyHash = varchar("key_hash", 64)
    val keyPrefix = varchar("key_prefix", 12)
    val environment = varchar("environment", 10)
    val name = varchar("name", 100).nullable()
    val lastUsedAt = timestamp("last_used_at").nullable()
    val createdAt = timestamp("created_at")
    val revokedAt = timestamp("revoked_at").nullable()
}

object Webhooks : UUIDTable("webhooks") {
    val organizationId = reference("organization_id", Organizations)
    val url = varchar("url", 2048)
    val secret = varchar("secret", 64)
    val events = array<String>("events")
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at")
}

object Templates : UUIDTable("templates") {
    val organizationId = reference("organization_id", Organizations).nullable()
    val name = varchar("name", 100)
    val displayName = varchar("display_name", 255).nullable()
    val description = text("description").nullable()
    val templateType = varchar("template_type", 20)
    val dcqlQuery = text("dcql_query")  // JSON stored as text
    val responseMode = varchar("response_mode", 20).default("answers")
    val claimMappings = text("claim_mappings").nullable()
    val validCredentialTypes = array<String>("valid_credential_types").nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, name)
    }
}

object Orchestrations : UUIDTable("orchestrations") {
    val organizationId = reference("organization_id", Organizations)
    val name = varchar("name", 100)
    val steps = text("steps")  // JSON
    val onComplete = text("on_complete").nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, name)
    }
}
```

**Step 2: Create DatabaseConfig.kt**

```kotlin
package id.walt.verifyapi.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.configureDatabase() {
    val dbUrl = environment.config.propertyOrNull("database.url")?.getString()
        ?: "jdbc:postgresql://localhost:5432/verify_api"
    val dbUser = environment.config.propertyOrNull("database.user")?.getString() ?: "postgres"
    val dbPassword = environment.config.propertyOrNull("database.password")?.getString() ?: "postgres"

    val config = HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        maximumPoolSize = 10
    }

    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)

    transaction {
        SchemaUtils.createMissingTablesAndColumns(
            Organizations,
            ApiKeys,
            Webhooks,
            Templates,
            Orchestrations
        )
    }

    // Seed system templates
    seedSystemTemplates()
}

private fun seedSystemTemplates() {
    transaction {
        // Insert system templates if not exist
        // (implementation in next task)
    }
}
```

**Step 3: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/db/
git commit -m "feat(verify-api): add database schema"
```

---

### Task 1.3: Session management with Valkey

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/session/SessionManager.kt`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/session/VerificationSession.kt`

**Step 1: Create session data classes**

```kotlin
// VerificationSession.kt
package id.walt.verifyapi.session

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class VerificationSession(
    val id: String,
    val organizationId: String,
    val templateName: String,
    val responseMode: ResponseMode,
    val status: SessionStatus,
    val verifierSessionId: String? = null,
    val createdAt: Long,  // Epoch millis for serialization
    val expiresAt: Long,
    val result: VerificationResult? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
enum class SessionStatus {
    PENDING, VERIFIED, FAILED, EXPIRED
}

@Serializable
enum class ResponseMode {
    ANSWERS, RAW_CREDENTIALS
}

@Serializable
data class VerificationResult(
    val answers: Map<String, String>? = null,
    val credentials: List<RawCredential>? = null,
    val verifiedAt: Long
)

@Serializable
data class RawCredential(
    val format: String,
    val vct: String? = null,
    val doctype: String? = null,
    val raw: String,
    val disclosedClaims: Map<String, String>
)
```

**Step 2: Create SessionManager**

```kotlin
// SessionManager.kt
package id.walt.verifyapi.session

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class SessionManager(valkeyUrl: String) {
    private val client: RedisClient = RedisClient.create(valkeyUrl)
    private val connection: StatefulRedisConnection<String, String> = client.connect()
    private val commands = connection.sync()
    private val json = Json { ignoreUnknownKeys = true }

    private val sessionTtl = 5.minutes.inWholeSeconds

    fun createSession(
        organizationId: UUID,
        templateName: String,
        responseMode: ResponseMode,
        metadata: Map<String, String>?
    ): VerificationSession {
        val sessionId = "vs_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val now = System.currentTimeMillis()

        val session = VerificationSession(
            id = sessionId,
            organizationId = organizationId.toString(),
            templateName = templateName,
            responseMode = responseMode,
            status = SessionStatus.PENDING,
            createdAt = now,
            expiresAt = now + (sessionTtl * 1000),
            metadata = metadata
        )

        val key = "session:$sessionId"
        commands.setex(key, sessionTtl, json.encodeToString(session))

        return session
    }

    fun getSession(sessionId: String): VerificationSession? {
        val key = "session:$sessionId"
        val data = commands.get(key) ?: return null
        return json.decodeFromString<VerificationSession>(data)
    }

    fun updateSession(session: VerificationSession): VerificationSession {
        val key = "session:${session.id}"
        val ttl = commands.ttl(key)
        if (ttl > 0) {
            commands.setex(key, ttl, json.encodeToString(session))
        }
        return session
    }

    fun close() {
        connection.close()
        client.shutdown()
    }
}
```

**Step 3: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/session/
git commit -m "feat(verify-api): add session management with Valkey"
```

---

## Phase 2: Core API Endpoints

### Task 2.1: API key authentication

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/auth/ApiKeyAuth.kt`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/auth/AuthConfig.kt`

**Step 1: Create API key authentication**

```kotlin
// ApiKeyAuth.kt
package id.walt.verifyapi.auth

import id.walt.verifyapi.db.ApiKeys
import id.walt.verifyapi.db.Organizations
import io.ktor.server.auth.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.util.UUID
import kotlinx.datetime.Clock

data class ApiKeyPrincipal(
    val organizationId: UUID,
    val organizationName: String,
    val environment: String,
    val keyId: UUID
) : Principal

class ApiKeyAuthProvider(private val name: String?) : AuthenticationProvider(Config(name)) {
    class Config(name: String?) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val authHeader = call.request.headers["Authorization"]

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            context.challenge("ApiKey", AuthenticationFailedCause.NoCredentials) { challenge, _ ->
                challenge.complete()
            }
            return
        }

        val apiKey = authHeader.removePrefix("Bearer ")
        val principal = validateApiKey(apiKey)

        if (principal == null) {
            context.challenge("ApiKey", AuthenticationFailedCause.InvalidCredentials) { challenge, _ ->
                challenge.complete()
            }
            return
        }

        context.principal(principal)
    }

    private fun validateApiKey(apiKey: String): ApiKeyPrincipal? {
        val keyHash = hashApiKey(apiKey)
        val prefix = apiKey.take(12)

        return transaction {
            val row = (ApiKeys innerJoin Organizations)
                .selectAll()
                .where {
                    (ApiKeys.keyHash eq keyHash) and
                    (ApiKeys.keyPrefix eq prefix) and
                    (ApiKeys.revokedAt.isNull())
                }
                .singleOrNull() ?: return@transaction null

            // Update last used timestamp
            ApiKeys.update({ ApiKeys.id eq row[ApiKeys.id] }) {
                it[lastUsedAt] = Clock.System.now()
            }

            ApiKeyPrincipal(
                organizationId = row[Organizations.id].value,
                organizationName = row[Organizations.name],
                environment = row[ApiKeys.environment],
                keyId = row[ApiKeys.id].value
            )
        }
    }

    companion object {
        fun hashApiKey(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

fun AuthenticationConfig.apiKey(name: String? = null) {
    register(ApiKeyAuthProvider(name))
}
```

**Step 2: Create AuthConfig.kt**

```kotlin
// AuthConfig.kt
package id.walt.verifyapi.auth

import io.ktor.server.application.*
import io.ktor.server.auth.*

fun Application.configureAuthentication() {
    install(Authentication) {
        apiKey("api-key")
    }
}
```

**Step 3: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/auth/
git commit -m "feat(verify-api): add API key authentication"
```

---

### Task 2.2: Identity verification endpoint

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/VerifyRoutes.kt`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/service/VerificationService.kt`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/service/QrCodeGenerator.kt`

**Step 1: Create request/response DTOs**

```kotlin
// VerifyRoutes.kt (DTOs section)
package id.walt.verifyapi.routes

import kotlinx.serialization.Serializable

@Serializable
data class IdentityVerifyRequest(
    val template: String,
    val responseMode: String = "answers",
    val redirectUri: String? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class VerifyResponse(
    val sessionId: String,
    val qrCodeUrl: String,
    val qrCodeData: String,
    val deepLink: String,
    val expiresAt: String
)
```

**Step 2: Create VerificationService**

```kotlin
// VerificationService.kt
package id.walt.verifyapi.service

import id.walt.verifyapi.db.Templates
import id.walt.verifyapi.session.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class VerificationService(
    private val sessionManager: SessionManager,
    private val httpClient: HttpClient,
    private val verifierApi2Url: String,
    private val publicBaseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createIdentityVerification(
        organizationId: UUID,
        templateName: String,
        responseMode: ResponseMode,
        redirectUri: String?,
        metadata: Map<String, String>?
    ): VerifyResult {
        // 1. Load template
        val template = loadTemplate(organizationId, templateName)
            ?: return VerifyResult.Error("Template not found: $templateName")

        // 2. Create session
        val session = sessionManager.createSession(
            organizationId = organizationId,
            templateName = templateName,
            responseMode = responseMode,
            metadata = metadata
        )

        // 3. Create verification session in verifier-api2
        val verifierSession = createVerifierSession(template.dcqlQuery)

        // 4. Update session with verifier session ID
        val updatedSession = session.copy(verifierSessionId = verifierSession.sessionId)
        sessionManager.updateSession(updatedSession)

        // 5. Generate QR code URL
        val qrCodeUrl = "$publicBaseUrl/qr/${session.id}.png"
        val deepLink = "eudi-openid4vp://${verifierSession.authorizationUrl}"

        return VerifyResult.Success(
            sessionId = session.id,
            qrCodeUrl = qrCodeUrl,
            qrCodeData = verifierSession.authorizationUrl,
            deepLink = deepLink,
            expiresAt = session.expiresAt
        )
    }

    private fun loadTemplate(organizationId: UUID, name: String): TemplateData? {
        return transaction {
            Templates.selectAll()
                .where {
                    (Templates.name eq name) and
                    ((Templates.organizationId eq organizationId) or (Templates.organizationId.isNull()))
                }
                .orderBy(Templates.organizationId)  // Prefer org-specific over system
                .firstOrNull()
                ?.let {
                    TemplateData(
                        name = it[Templates.name],
                        dcqlQuery = it[Templates.dcqlQuery],
                        responseMode = it[Templates.responseMode],
                        claimMappings = it[Templates.claimMappings]
                    )
                }
        }
    }

    private suspend fun createVerifierSession(dcqlQuery: String): VerifierSessionResult {
        val response = httpClient.post("$verifierApi2Url/openid4vp/session") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("flow_type", "cross_device")
                put("core_flow", buildJsonObject {
                    put("signed_request", true)
                    put("dcql_query", json.parseToJsonElement(dcqlQuery))
                })
            }.toString())
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return VerifierSessionResult(
            sessionId = body["session_id"]?.jsonPrimitive?.content ?: "",
            authorizationUrl = body["authorization_request"]?.jsonPrimitive?.content ?: ""
        )
    }
}

data class TemplateData(
    val name: String,
    val dcqlQuery: String,
    val responseMode: String,
    val claimMappings: String?
)

data class VerifierSessionResult(
    val sessionId: String,
    val authorizationUrl: String
)

sealed class VerifyResult {
    data class Success(
        val sessionId: String,
        val qrCodeUrl: String,
        val qrCodeData: String,
        val deepLink: String,
        val expiresAt: Long
    ) : VerifyResult()

    data class Error(val message: String) : VerifyResult()
}
```

**Step 3: Create QR code generator**

```kotlin
// QrCodeGenerator.kt
package id.walt.verifyapi.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object QrCodeGenerator {
    fun generate(data: String, size: Int = 300): ByteArray {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
        val image = MatrixToImageWriter.toBufferedImage(bitMatrix)

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", outputStream)
        return outputStream.toByteArray()
    }
}
```

**Step 4: Create routes**

```kotlin
// VerifyRoutes.kt (routes section)
fun Route.verifyRoutes(verificationService: VerificationService) {
    authenticate("api-key") {
        route("/v1/verify") {
            post("/identity") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<IdentityVerifyRequest>()

                val responseMode = when (request.responseMode) {
                    "raw_credentials" -> ResponseMode.RAW_CREDENTIALS
                    else -> ResponseMode.ANSWERS
                }

                when (val result = verificationService.createIdentityVerification(
                    organizationId = principal.organizationId,
                    templateName = request.template,
                    responseMode = responseMode,
                    redirectUri = request.redirectUri,
                    metadata = request.metadata
                )) {
                    is VerifyResult.Success -> call.respond(HttpStatusCode.Created, VerifyResponse(
                        sessionId = result.sessionId,
                        qrCodeUrl = result.qrCodeUrl,
                        qrCodeData = result.qrCodeData,
                        deepLink = result.deepLink,
                        expiresAt = Instant.fromEpochMilliseconds(result.expiresAt).toString()
                    ))
                    is VerifyResult.Error -> call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to result.message)
                    )
                }
            }
        }
    }

    // Public QR code endpoint (no auth)
    get("/qr/{sessionId}.png") {
        val sessionId = call.parameters["sessionId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)

        val session = verificationService.getSession(sessionId)
            ?: return@get call.respond(HttpStatusCode.NotFound)

        val qrData = session.verifierSessionId ?: return@get call.respond(HttpStatusCode.NotFound)
        val qrBytes = QrCodeGenerator.generate(qrData)

        call.respondBytes(qrBytes, ContentType.Image.PNG)
    }
}
```

**Step 5: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/service/
git commit -m "feat(verify-api): add identity verification endpoint"
```

---

### Task 2.3: Session status endpoint

**Files:**
- Modify: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/VerifyRoutes.kt`

**Step 1: Add session status route**

```kotlin
// Add to VerifyRoutes.kt
authenticate("api-key") {
    route("/v1/sessions") {
        get("/{sessionId}") {
            val principal = call.principal<ApiKeyPrincipal>()!!
            val sessionId = call.parameters["sessionId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val session = verificationService.getSession(sessionId)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            // Verify org ownership
            if (session.organizationId != principal.organizationId.toString()) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            call.respond(SessionStatusResponse(
                sessionId = session.id,
                status = session.status.name.lowercase(),
                result = session.result?.let { result ->
                    ResultResponse(
                        answers = result.answers,
                        credentials = result.credentials?.map { cred ->
                            CredentialResponse(
                                format = cred.format,
                                vct = cred.vct,
                                raw = cred.raw,
                                disclosedClaims = cred.disclosedClaims
                            )
                        }
                    )
                },
                verifiedAt = session.result?.verifiedAt?.let {
                    Instant.fromEpochMilliseconds(it).toString()
                },
                metadata = session.metadata
            ))
        }
    }
}

@Serializable
data class SessionStatusResponse(
    val sessionId: String,
    val status: String,
    val result: ResultResponse? = null,
    val verifiedAt: String? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class ResultResponse(
    val answers: Map<String, String>? = null,
    val credentials: List<CredentialResponse>? = null
)

@Serializable
data class CredentialResponse(
    val format: String,
    val vct: String? = null,
    val raw: String,
    val disclosedClaims: Map<String, String>
)
```

**Step 2: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/
git commit -m "feat(verify-api): add session status endpoint"
```

---

### Task 2.4: Template management endpoints

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/TemplateRoutes.kt`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/service/TemplateService.kt`

**Step 1: Create TemplateService**

```kotlin
// TemplateService.kt
package id.walt.verifyapi.service

import id.walt.verifyapi.db.Templates
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class TemplateService {
    private val json = Json { ignoreUnknownKeys = true }

    fun listTemplates(organizationId: UUID): List<TemplateInfo> {
        return transaction {
            Templates.selectAll()
                .where {
                    (Templates.organizationId eq organizationId) or
                    (Templates.organizationId.isNull())
                }
                .map { row ->
                    TemplateInfo(
                        name = row[Templates.name],
                        displayName = row[Templates.displayName],
                        type = row[Templates.templateType],
                        description = row[Templates.description],
                        isSystem = row[Templates.organizationId] == null
                    )
                }
        }
    }

    fun createTemplate(
        organizationId: UUID,
        name: String,
        displayName: String?,
        type: String,
        claims: List<ClaimDefinition>,
        validCredentials: List<String>?
    ): TemplateInfo {
        val dcqlQuery = buildDcqlQuery(claims, type)
        val claimMappings = buildClaimMappings(claims)

        transaction {
            Templates.insert {
                it[Templates.organizationId] = organizationId
                it[Templates.name] = name
                it[Templates.displayName] = displayName
                it[templateType] = type
                it[Templates.dcqlQuery] = dcqlQuery
                it[Templates.claimMappings] = claimMappings
                it[validCredentialTypes] = validCredentials?.toTypedArray()
                it[createdAt] = Clock.System.now()
            }
        }

        return TemplateInfo(
            name = name,
            displayName = displayName,
            type = type,
            description = null,
            isSystem = false
        )
    }

    private fun buildDcqlQuery(claims: List<ClaimDefinition>, type: String): String {
        val vct = if (type == "payment") "PaymentWalletAttestation" else "urn:eudi:pid:1"

        return buildJsonObject {
            putJsonArray("credentials") {
                addJsonObject {
                    put("id", "cred")
                    put("format", "dc+sd-jwt")
                    putJsonObject("meta") {
                        putJsonArray("vct_values") { add(vct) }
                    }
                    putJsonArray("claims") {
                        claims.forEach { claim ->
                            addJsonObject {
                                putJsonArray("path") {
                                    claim.path.forEach { add(it) }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    private fun buildClaimMappings(claims: List<ClaimDefinition>): String {
        return buildJsonObject {
            claims.forEach { claim ->
                val path = claim.path.joinToString(".")
                val alias = claim.alias ?: claim.path.last()
                put(path, alias)
            }
        }.toString()
    }
}

data class TemplateInfo(
    val name: String,
    val displayName: String?,
    val type: String,
    val description: String?,
    val isSystem: Boolean
)

data class ClaimDefinition(
    val path: List<String>,
    val required: Boolean = true,
    val alias: String? = null
)
```

**Step 2: Create routes**

```kotlin
// TemplateRoutes.kt
package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.service.ClaimDefinition
import id.walt.verifyapi.service.TemplateService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class CreateTemplateRequest(
    val name: String,
    val displayName: String? = null,
    val type: String,
    val claims: List<ClaimRequest>,
    val validCredentials: List<String>? = null
)

@Serializable
data class ClaimRequest(
    val path: List<String>,
    val required: Boolean = true,
    val alias: String? = null
)

@Serializable
data class TemplateResponse(
    val name: String,
    val displayName: String?,
    val type: String,
    val description: String?,
    val isSystem: Boolean
)

fun Route.templateRoutes(templateService: TemplateService) {
    authenticate("api-key") {
        route("/v1/templates") {
            get {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val templates = templateService.listTemplates(principal.organizationId)

                call.respond(templates.map {
                    TemplateResponse(
                        name = it.name,
                        displayName = it.displayName,
                        type = it.type,
                        description = it.description,
                        isSystem = it.isSystem
                    )
                })
            }

            post {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<CreateTemplateRequest>()

                // Validate name format
                if (!request.name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Template name must be lowercase alphanumeric with underscores")
                    )
                }

                val template = templateService.createTemplate(
                    organizationId = principal.organizationId,
                    name = request.name,
                    displayName = request.displayName,
                    type = request.type,
                    claims = request.claims.map {
                        ClaimDefinition(it.path, it.required, it.alias)
                    },
                    validCredentials = request.validCredentials
                )

                call.respond(HttpStatusCode.Created, TemplateResponse(
                    name = template.name,
                    displayName = template.displayName,
                    type = template.type,
                    description = template.description,
                    isSystem = template.isSystem
                ))
            }
        }
    }
}
```

**Step 3: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/TemplateRoutes.kt
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/service/TemplateService.kt
git commit -m "feat(verify-api): add template management endpoints"
```

---

## Phase 3: Webhook System

### Task 3.1: Webhook dispatcher

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/webhook/WebhookDispatcher.kt`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/webhook/WebhookPayload.kt`

**Step 1: Create webhook payload types**

```kotlin
// WebhookPayload.kt
package id.walt.verifyapi.webhook

import kotlinx.serialization.Serializable

@Serializable
data class WebhookPayload(
    val event: String,
    val timestamp: String,
    val data: WebhookData
)

@Serializable
data class WebhookData(
    val sessionId: String,
    val template: String,
    val status: String,
    val result: WebhookResult? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class WebhookResult(
    val answers: Map<String, String>? = null,
    val credentials: List<WebhookCredential>? = null
)

@Serializable
data class WebhookCredential(
    val format: String,
    val vct: String? = null,
    val raw: String,
    val disclosedClaims: Map<String, String>
)
```

**Step 2: Create WebhookDispatcher**

```kotlin
// WebhookDispatcher.kt
package id.walt.verifyapi.webhook

import id.walt.verifyapi.db.Webhooks
import id.walt.verifyapi.session.VerificationSession
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookDispatcher(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val json = Json { encodeDefaults = true }
    private val maxRetries = 3
    private val retryDelays = listOf(1000L, 5000L, 30000L)  // 1s, 5s, 30s

    fun dispatchVerificationCompleted(session: VerificationSession) {
        val event = "verification.completed"
        dispatchEvent(UUID.fromString(session.organizationId), event, session)
    }

    fun dispatchVerificationFailed(session: VerificationSession) {
        val event = "verification.failed"
        dispatchEvent(UUID.fromString(session.organizationId), event, session)
    }

    private fun dispatchEvent(organizationId: UUID, event: String, session: VerificationSession) {
        val webhooks = transaction {
            Webhooks.selectAll()
                .where {
                    (Webhooks.organizationId eq organizationId) and
                    (Webhooks.enabled eq true)
                }
                .filter { event in it[Webhooks.events] }
                .map { WebhookConfig(
                    url = it[Webhooks.url],
                    secret = it[Webhooks.secret]
                )}
        }

        val payload = buildPayload(event, session)

        webhooks.forEach { webhook ->
            scope.launch {
                deliverWithRetry(webhook, payload)
            }
        }
    }

    private fun buildPayload(event: String, session: VerificationSession): WebhookPayload {
        return WebhookPayload(
            event = event,
            timestamp = Clock.System.now().toString(),
            data = WebhookData(
                sessionId = session.id,
                template = session.templateName,
                status = session.status.name.lowercase(),
                result = session.result?.let { result ->
                    WebhookResult(
                        answers = result.answers,
                        credentials = result.credentials?.map { cred ->
                            WebhookCredential(
                                format = cred.format,
                                vct = cred.vct,
                                raw = cred.raw,
                                disclosedClaims = cred.disclosedClaims
                            )
                        }
                    )
                },
                metadata = session.metadata
            )
        )
    }

    private suspend fun deliverWithRetry(webhook: WebhookConfig, payload: WebhookPayload) {
        val body = json.encodeToString(payload)
        val timestamp = System.currentTimeMillis().toString()
        val signature = sign(webhook.secret, "$timestamp.$body")

        repeat(maxRetries) { attempt ->
            try {
                val response = httpClient.post(webhook.url) {
                    contentType(ContentType.Application.Json)
                    header("X-Verify-Signature", signature)
                    header("X-Verify-Timestamp", timestamp)
                    setBody(body)
                }

                if (response.status.isSuccess()) {
                    return  // Success, stop retrying
                }
            } catch (e: Exception) {
                // Log error, continue to retry
            }

            if (attempt < maxRetries - 1) {
                delay(retryDelays[attempt])
            }
        }
    }

    private fun sign(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private data class WebhookConfig(
    val url: String,
    val secret: String
)
```

**Step 3: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/webhook/
git commit -m "feat(verify-api): add webhook dispatcher with retry"
```

---

### Task 3.2: Webhook management endpoints

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/WebhookRoutes.kt`

**Step 1: Create webhook routes**

```kotlin
// WebhookRoutes.kt
package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.db.Webhooks
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom

@Serializable
data class CreateWebhookRequest(
    val url: String,
    val events: List<String>,
    val secret: String? = null
)

@Serializable
data class WebhookResponse(
    val id: String,
    val url: String,
    val events: List<String>,
    val enabled: Boolean,
    val secret: String? = null  // Only returned on creation
)

fun Route.webhookRoutes() {
    authenticate("api-key") {
        route("/v1/webhooks") {
            get {
                val principal = call.principal<ApiKeyPrincipal>()!!

                val webhooks = transaction {
                    Webhooks.selectAll()
                        .where { Webhooks.organizationId eq principal.organizationId }
                        .map { row ->
                            WebhookResponse(
                                id = row[Webhooks.id].value.toString(),
                                url = row[Webhooks.url],
                                events = row[Webhooks.events].toList(),
                                enabled = row[Webhooks.enabled]
                            )
                        }
                }

                call.respond(webhooks)
            }

            post {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<CreateWebhookRequest>()

                // Validate events
                val validEvents = setOf(
                    "verification.completed",
                    "verification.failed",
                    "verification.expired",
                    "orchestration.step_completed",
                    "orchestration.completed"
                )

                val invalidEvents = request.events - validEvents
                if (invalidEvents.isNotEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid events: $invalidEvents")
                    )
                }

                val secret = request.secret ?: generateSecret()

                val id = transaction {
                    Webhooks.insertAndGetId {
                        it[organizationId] = principal.organizationId
                        it[url] = request.url
                        it[Webhooks.secret] = secret
                        it[events] = request.events.toTypedArray()
                        it[enabled] = true
                        it[createdAt] = Clock.System.now()
                    }
                }

                call.respond(HttpStatusCode.Created, WebhookResponse(
                    id = id.value.toString(),
                    url = request.url,
                    events = request.events,
                    enabled = true,
                    secret = secret  // Return secret only on creation
                ))
            }

            delete("/{webhookId}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val webhookId = call.parameters["webhookId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                val deleted = transaction {
                    Webhooks.deleteWhere {
                        (Webhooks.id eq java.util.UUID.fromString(webhookId)) and
                        (organizationId eq principal.organizationId)
                    }
                }

                if (deleted > 0) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

private fun generateSecret(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return "whsec_" + bytes.joinToString("") { "%02x".format(it) }
}
```

**Step 2: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/WebhookRoutes.kt
git commit -m "feat(verify-api): add webhook management endpoints"
```

---

## Phase 4: Orchestration Support

### Task 4.1: Orchestration engine

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/orchestration/OrchestrationEngine.kt`
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/orchestration/OrchestrationSession.kt`

**Step 1: Create orchestration data model**

```kotlin
// OrchestrationSession.kt
package id.walt.verifyapi.orchestration

import kotlinx.serialization.Serializable

@Serializable
data class OrchestrationDefinition(
    val id: String,
    val name: String,
    val steps: List<OrchestrationStep>,
    val onComplete: OnCompleteConfig? = null
)

@Serializable
data class OrchestrationStep(
    val id: String,
    val type: String,  // "identity" or "payment"
    val template: String,
    val dependsOn: List<String> = emptyList(),
    val config: Map<String, String> = emptyMap()
)

@Serializable
data class OnCompleteConfig(
    val webhook: String? = null,
    val redirect: String? = null
)

@Serializable
data class OrchestrationSession(
    val id: String,
    val orchestrationId: String,
    val organizationId: String,
    val status: OrchestrationStatus,
    val currentStepId: String?,
    val currentVerificationSessionId: String?,
    val stepResults: Map<String, StepResult>,
    val input: Map<String, String>,
    val createdAt: Long,
    val completedAt: Long? = null
)

@Serializable
enum class OrchestrationStatus {
    IN_PROGRESS, COMPLETED, FAILED
}

@Serializable
data class StepResult(
    val status: String,
    val answers: Map<String, String>? = null
)
```

**Step 2: Create OrchestrationEngine**

```kotlin
// OrchestrationEngine.kt
package id.walt.verifyapi.orchestration

import id.walt.verifyapi.db.Orchestrations
import id.walt.verifyapi.service.VerificationService
import id.walt.verifyapi.session.ResponseMode
import id.walt.verifyapi.session.SessionManager
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class OrchestrationEngine(
    private val sessionManager: SessionManager,
    private val verificationService: VerificationService,
    private val redisCommands: RedisCommands<String, String>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionTtl = 3600L  // 1 hour

    fun loadOrchestration(organizationId: UUID, orchestrationId: String): OrchestrationDefinition? {
        return transaction {
            Orchestrations.selectAll()
                .where { Orchestrations.id eq UUID.fromString(orchestrationId) }
                .singleOrNull()
                ?.let { row ->
                    OrchestrationDefinition(
                        id = row[Orchestrations.id].value.toString(),
                        name = row[Orchestrations.name],
                        steps = json.decodeFromString(row[Orchestrations.steps]),
                        onComplete = row[Orchestrations.onComplete]?.let { json.decodeFromString(it) }
                    )
                }
        }
    }

    suspend fun startOrchestration(
        orchestration: OrchestrationDefinition,
        organizationId: UUID,
        input: Map<String, String>
    ): OrchestrationStartResult {
        val sessionId = "orch_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        // Find first step (no dependencies)
        val firstStep = orchestration.steps.find { it.dependsOn.isEmpty() }
            ?: return OrchestrationStartResult.Error("No initial step found")

        // Create verification session for first step
        val verifyResult = verificationService.createIdentityVerification(
            organizationId = organizationId,
            templateName = firstStep.template,
            responseMode = ResponseMode.ANSWERS,
            redirectUri = null,
            metadata = mapOf("orchestration_session_id" to sessionId)
        )

        val verificationSessionId = when (verifyResult) {
            is id.walt.verifyapi.service.VerifyResult.Success -> verifyResult.sessionId
            is id.walt.verifyapi.service.VerifyResult.Error ->
                return OrchestrationStartResult.Error(verifyResult.message)
        }

        // Create orchestration session
        val session = OrchestrationSession(
            id = sessionId,
            orchestrationId = orchestration.id,
            organizationId = organizationId.toString(),
            status = OrchestrationStatus.IN_PROGRESS,
            currentStepId = firstStep.id,
            currentVerificationSessionId = verificationSessionId,
            stepResults = emptyMap(),
            input = input,
            createdAt = System.currentTimeMillis()
        )

        // Store in Redis
        redisCommands.setex(
            "orchestration:$sessionId",
            sessionTtl,
            json.encodeToString(session)
        )

        return OrchestrationStartResult.Success(
            orchestrationSessionId = sessionId,
            currentStep = firstStep.id,
            verificationSessionId = verificationSessionId
        )
    }

    fun getOrchestrationSession(sessionId: String): OrchestrationSession? {
        val data = redisCommands.get("orchestration:$sessionId") ?: return null
        return json.decodeFromString(data)
    }

    suspend fun advanceOrchestration(
        sessionId: String,
        stepResult: StepResult
    ): OrchestrationAdvanceResult {
        val session = getOrchestrationSession(sessionId)
            ?: return OrchestrationAdvanceResult.Error("Session not found")

        val orchestration = loadOrchestration(
            UUID.fromString(session.organizationId),
            session.orchestrationId
        ) ?: return OrchestrationAdvanceResult.Error("Orchestration not found")

        // Update step results
        val updatedResults = session.stepResults + (session.currentStepId!! to stepResult)

        // Find next eligible step
        val completedSteps = updatedResults.keys
        val nextStep = orchestration.steps.find { step ->
            step.id !in completedSteps && step.dependsOn.all { it in completedSteps }
        }

        return if (nextStep != null) {
            // Create verification session for next step
            val verifyResult = verificationService.createIdentityVerification(
                organizationId = UUID.fromString(session.organizationId),
                templateName = nextStep.template,
                responseMode = ResponseMode.ANSWERS,
                redirectUri = null,
                metadata = mapOf("orchestration_session_id" to sessionId)
            )

            val verificationSessionId = when (verifyResult) {
                is id.walt.verifyapi.service.VerifyResult.Success -> verifyResult.sessionId
                is id.walt.verifyapi.service.VerifyResult.Error ->
                    return OrchestrationAdvanceResult.Error(verifyResult.message)
            }

            // Update session
            val updatedSession = session.copy(
                currentStepId = nextStep.id,
                currentVerificationSessionId = verificationSessionId,
                stepResults = updatedResults
            )

            redisCommands.setex(
                "orchestration:$sessionId",
                sessionTtl,
                json.encodeToString(updatedSession)
            )

            OrchestrationAdvanceResult.NextStep(
                nextStepId = nextStep.id,
                verificationSessionId = verificationSessionId
            )
        } else {
            // Orchestration complete
            val completedSession = session.copy(
                status = OrchestrationStatus.COMPLETED,
                currentStepId = null,
                currentVerificationSessionId = null,
                stepResults = updatedResults,
                completedAt = System.currentTimeMillis()
            )

            redisCommands.setex(
                "orchestration:$sessionId",
                sessionTtl,
                json.encodeToString(completedSession)
            )

            OrchestrationAdvanceResult.Completed(updatedResults)
        }
    }
}

sealed class OrchestrationStartResult {
    data class Success(
        val orchestrationSessionId: String,
        val currentStep: String,
        val verificationSessionId: String
    ) : OrchestrationStartResult()

    data class Error(val message: String) : OrchestrationStartResult()
}

sealed class OrchestrationAdvanceResult {
    data class NextStep(
        val nextStepId: String,
        val verificationSessionId: String
    ) : OrchestrationAdvanceResult()

    data class Completed(
        val results: Map<String, StepResult>
    ) : OrchestrationAdvanceResult()

    data class Error(val message: String) : OrchestrationAdvanceResult()
}
```

**Step 3: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/orchestration/
git commit -m "feat(verify-api): add orchestration engine"
```

---

### Task 4.2: Orchestration API endpoints

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/OrchestrationRoutes.kt`

**Step 1: Create orchestration routes**

```kotlin
// OrchestrationRoutes.kt
package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.db.Orchestrations
import id.walt.verifyapi.orchestration.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class CreateOrchestrationRequest(
    val name: String,
    val steps: List<OrchestrationStepRequest>,
    val onComplete: OnCompleteRequest? = null
)

@Serializable
data class OrchestrationStepRequest(
    val id: String,
    val type: String,
    val template: String,
    val dependsOn: List<String> = emptyList(),
    val config: Map<String, String> = emptyMap()
)

@Serializable
data class OnCompleteRequest(
    val webhook: String? = null,
    val redirect: String? = null
)

@Serializable
data class StartOrchestrationRequest(
    val input: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class OrchestrationResponse(
    val id: String,
    val name: String,
    val steps: List<OrchestrationStepRequest>
)

@Serializable
data class OrchestrationSessionResponse(
    val orchestrationSessionId: String,
    val currentStep: String,
    val verification: VerifyResponse
)

fun Route.orchestrationRoutes(
    orchestrationEngine: OrchestrationEngine,
    verificationService: id.walt.verifyapi.service.VerificationService
) {
    val json = Json { ignoreUnknownKeys = true }

    authenticate("api-key") {
        route("/v1/orchestrations") {
            // List orchestrations
            get {
                val principal = call.principal<ApiKeyPrincipal>()!!

                val orchestrations = transaction {
                    Orchestrations.selectAll()
                        .where { Orchestrations.organizationId eq principal.organizationId }
                        .map { row ->
                            OrchestrationResponse(
                                id = row[Orchestrations.id].value.toString(),
                                name = row[Orchestrations.name],
                                steps = json.decodeFromString(row[Orchestrations.steps])
                            )
                        }
                }

                call.respond(orchestrations)
            }

            // Create orchestration
            post {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<CreateOrchestrationRequest>()

                // Validate step dependencies
                val stepIds = request.steps.map { it.id }.toSet()
                for (step in request.steps) {
                    val invalidDeps = step.dependsOn - stepIds
                    if (invalidDeps.isNotEmpty()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid dependencies in step ${step.id}: $invalidDeps")
                        )
                    }
                }

                val id = transaction {
                    Orchestrations.insertAndGetId {
                        it[organizationId] = principal.organizationId
                        it[name] = request.name
                        it[steps] = json.encodeToString(request.steps)
                        it[onComplete] = request.onComplete?.let { oc -> json.encodeToString(oc) }
                        it[createdAt] = Clock.System.now()
                    }
                }

                call.respond(HttpStatusCode.Created, OrchestrationResponse(
                    id = id.value.toString(),
                    name = request.name,
                    steps = request.steps
                ))
            }

            // Start orchestration session
            post("/{orchestrationId}/sessions") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val orchestrationId = call.parameters["orchestrationId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<StartOrchestrationRequest>()

                val orchestration = orchestrationEngine.loadOrchestration(
                    principal.organizationId,
                    orchestrationId
                ) ?: return@post call.respond(HttpStatusCode.NotFound)

                when (val result = orchestrationEngine.startOrchestration(
                    orchestration = orchestration,
                    organizationId = principal.organizationId,
                    input = request.input
                )) {
                    is OrchestrationStartResult.Success -> {
                        // Get verification session details for response
                        val session = verificationService.getSession(result.verificationSessionId)!!

                        call.respond(HttpStatusCode.Created, mapOf(
                            "orchestration_session_id" to result.orchestrationSessionId,
                            "current_step" to result.currentStep,
                            "verification" to mapOf(
                                "session_id" to result.verificationSessionId,
                                "qr_code_url" to "...",  // Build from session
                                "expires_at" to session.expiresAt
                            )
                        ))
                    }
                    is OrchestrationStartResult.Error -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
                    }
                }
            }

            // Get orchestration session status
            get("/sessions/{sessionId}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val sessionId = call.parameters["sessionId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val session = orchestrationEngine.getOrchestrationSession(sessionId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                // Verify ownership
                if (session.organizationId != principal.organizationId.toString()) {
                    return@get call.respond(HttpStatusCode.NotFound)
                }

                call.respond(mapOf(
                    "orchestration_session_id" to session.id,
                    "status" to session.status.name.lowercase(),
                    "current_step" to session.currentStepId,
                    "step_results" to session.stepResults
                ))
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/routes/OrchestrationRoutes.kt
git commit -m "feat(verify-api): add orchestration API endpoints"
```

---

## Phase 5: OpenAPI Documentation

### Task 5.1: Swagger/OpenAPI setup

**Files:**
- Create: `waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/OpenApiConfig.kt`
- Create: `waltid-services/waltid-verify-api/src/main/resources/openapi/verify-api.yaml`

**Step 1: Configure Swagger plugin**

```kotlin
// OpenApiConfig.kt
package id.walt.verifyapi

import io.ktor.server.application.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*

fun Application.configureOpenAPI() {
    routing {
        swaggerUI(path = "docs", swaggerFile = "openapi/verify-api.yaml") {
            version = "5.17.2"
        }

        // Serve raw OpenAPI spec
        get("/api/v1/openapi.json") {
            call.respondText(
                this::class.java.classLoader
                    .getResourceAsStream("openapi/verify-api.yaml")!!
                    .bufferedReader().readText(),
                contentType = io.ktor.http.ContentType.Application.Json
            )
        }
    }
}
```

**Step 2: Create OpenAPI specification file**

Create comprehensive `verify-api.yaml` with all endpoints documented (content as specified in API Specification section above).

**Step 3: Commit**

```bash
git add waltid-services/waltid-verify-api/src/main/kotlin/id/walt/verifyapi/OpenApiConfig.kt
git add waltid-services/waltid-verify-api/src/main/resources/openapi/
git commit -m "feat(verify-api): add OpenAPI documentation"
```

---

## Phase 6: Docker & Deployment

### Task 6.1: Docker configuration

**Files:**
- Create: `waltid-services/waltid-verify-api/Dockerfile`
- Modify: `docker-compose/docker-compose.yaml`
- Create: `docker-compose/verify-api/config/application.conf`

**Step 1: Add to docker-compose.yaml**

```yaml
verify-api:
  image: ${IMAGE_PREFIX}waltid/verify-api:${VERSION_TAG:-latest}
  profiles:
    - services
    - identity
    - all
  pull_policy: missing
  depends_on:
    - postgres
    - valkey
    - verifier-api2
    - caddy
  env_file:
    - .env
  volumes:
    - ./verify-api/config:/waltid-verify-api/config
  environment:
    DATABASE_URL: "jdbc:postgresql://postgres:5432/${DB_NAME}"
    DATABASE_USER: "${DB_USERNAME}"
    DATABASE_PASSWORD: "${DB_PASSWORD}"
    VALKEY_URL: "redis://valkey:6379"
    VERIFIER_API2_URL: "http://verifier-api2:7004"
    PUBLIC_BASE_URL: "${VERIFY_API_EXTERNAL_URL:-http://$SERVICE_HOST:$VERIFY_API_PORT}"
```

**Step 2: Add port to Caddy**

Add verify-api port (7010) to Caddy configuration.

**Step 3: Add environment variables to .env**

```bash
VERIFY_API_PORT=7010
VERIFY_API_EXTERNAL_URL=http://localhost:7010
```

**Step 4: Commit**

```bash
git add docker-compose/docker-compose.yaml
git add docker-compose/verify-api/
git add docker-compose/.env
git commit -m "feat(verify-api): add Docker deployment configuration"
```

---

## Phase 7: Testing

### Task 7.1: Unit tests

**Files:**
- Create: `waltid-services/waltid-verify-api/src/test/kotlin/id/walt/verifyapi/ApiKeyAuthTest.kt`
- Create: `waltid-services/waltid-verify-api/src/test/kotlin/id/walt/verifyapi/SessionManagerTest.kt`
- Create: `waltid-services/waltid-verify-api/src/test/kotlin/id/walt/verifyapi/TemplateServiceTest.kt`

**Step 1: Write unit tests for each service**

(Implementation details for each test file)

**Step 2: Run tests**

```bash
./gradlew :waltid-services:waltid-verify-api:test
```

**Step 3: Commit**

```bash
git add waltid-services/waltid-verify-api/src/test/
git commit -m "test(verify-api): add unit tests"
```

---

### Task 7.2: Integration tests

**Files:**
- Create: `waltid-services/waltid-verify-api/src/test/kotlin/id/walt/verifyapi/VerifyApiIntegrationTest.kt`

**Step 1: Write end-to-end integration tests**

Test complete flow: create session → get QR → poll status

**Step 2: Commit**

```bash
git add waltid-services/waltid-verify-api/src/test/kotlin/id/walt/verifyapi/VerifyApiIntegrationTest.kt
git commit -m "test(verify-api): add integration tests"
```

---

## Summary

| Phase | Tasks | Est. Effort | Priority |
|-------|-------|-------------|----------|
| **Phase 0** | Isolation & feature flag infrastructure | 2-3 hours | **MANDATORY FIRST** |
| **Phase 1** | Project setup, DB schema, sessions | 4-6 hours | High |
| **Phase 2** | Core API endpoints | 6-8 hours | High |
| **Phase 3** | Webhook system | 3-4 hours | Medium |
| **Phase 4** | Orchestration | 4-6 hours | Medium |
| **Phase 5** | OpenAPI docs | 2-3 hours | Medium |
| **Phase 6** | Docker deployment | 2-3 hours | High |
| **Phase 7** | Testing | 4-6 hours | High |
| **Total** | 8 phases, 20 tasks | ~32-42 hours | |

---

## Success Criteria

### ⚠️ CRITICAL: Isolation (Must Pass First)

- [ ] `./scripts/verify-isolation.sh` passes
- [ ] `git diff waltid-services/waltid-issuer-api` shows NO changes
- [ ] `git diff waltid-services/waltid-verifier-api` shows NO changes
- [ ] `git diff waltid-services/waltid-verifier-api2` shows NO changes
- [ ] `git diff waltid-services/waltid-wallet-api` shows NO changes
- [ ] `git diff waltid-libraries` shows NO changes
- [ ] `docker compose --profile identity up` works WITHOUT verify-api
- [ ] All existing EUDI issuance flows work unchanged
- [ ] All existing EUDI verification flows work unchanged

### Feature Flag

- [ ] `VERIFY_API_ENABLED=false` → container exits cleanly, port not bound
- [ ] `VERIFY_API_ENABLED=true` → container starts and serves requests
- [ ] `--profile verify-api` required to start verify-api container

### Verify API Functionality

- [ ] All unit tests passing
- [ ] All integration tests passing
- [ ] OpenAPI spec validates with no errors
- [ ] Docker container starts and serves requests
- [ ] End-to-end flow works: API → QR → Wallet → Webhook
- [ ] Orchestration multi-step flow completes successfully
- [ ] Raw credentials mode returns full credential payloads
