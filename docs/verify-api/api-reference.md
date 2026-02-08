# Verify API Reference

This document covers the REST API for direct integration without using the SDKs.

## Base URL

```
Production: https://verify.example.com
Test:       https://verify-sandbox.example.com
```

## Authentication

All API requests require an API key in the Authorization header:

```http
Authorization: Bearer vfy_live_xxx
```

**Key Types:**
| Prefix | Environment | Billing |
|--------|-------------|---------|
| `vfy_test_` | Sandbox | No |
| `vfy_live_` | Production | Yes |
| `vfy_pub_` | Publishable (frontend) | Limited |

## Rate Limits

| Plan | Requests/min | Sessions/month |
|------|--------------|----------------|
| Free | 10 | 100 |
| Starter | 60 | 1,000 |
| Pro | 300 | 10,000 |
| Enterprise | Custom | Custom |

Rate limit headers are included in all responses:
```http
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 55
X-RateLimit-Reset: 1707000000
```

---

## Endpoints

### Create Verification Session

Start a new identity verification session.

```http
POST /v1/verify/identity
```

**Request Body:**

```json
{
  "template": "kyc_basic",
  "response_mode": "answers",
  "redirect_uri": "https://example.com/callback",
  "callback_url": "https://example.com/webhooks/verify",
  "metadata": {
    "userId": "user_123",
    "orderId": "order_456"
  },
  "expires_in": 600
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `template` | string | Yes* | Template name (e.g., `age_check`, `kyc_basic`) |
| `dcql` | object | Yes* | Custom DCQL query (alternative to template) |
| `response_mode` | string | No | `answers` (default) or `raw_credentials` |
| `redirect_uri` | string | No | URL to redirect user after verification |
| `callback_url` | string | No | Webhook URL for result notification |
| `metadata` | object | No | Custom data returned in webhook |
| `expires_in` | number | No | Session expiry in seconds (default: 600) |

*Either `template` or `dcql` is required.

**Response (201 Created):**

```json
{
  "session_id": "vs_abc123def456",
  "qr_code_url": "https://verify.example.com/qr/vs_abc123def456.png",
  "qr_code_data": "openid4vp://authorize?request_uri=...",
  "deep_link": "https://wallet.example.com/verify?request_uri=...",
  "expires_at": "2026-02-08T11:00:00Z"
}
```

**cURL Example:**

```bash
curl -X POST https://verify.example.com/v1/verify/identity \
  -H "Authorization: Bearer vfy_live_xxx" \
  -H "Content-Type: application/json" \
  -d '{
    "template": "kyc_basic",
    "metadata": {"userId": "user_123"}
  }'
```

---

### Create Payment Verification Session

Start a payment authorization verification session.

```http
POST /v1/verify/payment
```

**Request Body:**

```json
{
  "template": "payment_authorization",
  "transaction": {
    "amount": "150.00",
    "currency": "EUR",
    "merchant_name": "Example Shop",
    "merchant_id": "merchant_123",
    "reference": "TXN-456"
  },
  "callback_url": "https://example.com/webhooks/payment",
  "metadata": {
    "transactionId": "txn_789"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `template` | string | Yes | Payment template name |
| `transaction` | object | Yes | Transaction details |
| `transaction.amount` | string | Yes | Transaction amount |
| `transaction.currency` | string | Yes | ISO 4217 currency code |
| `transaction.merchant_name` | string | Yes | Merchant display name |
| `transaction.merchant_id` | string | No | Merchant identifier |
| `transaction.reference` | string | No | Your transaction reference |
| `callback_url` | string | No | Webhook URL |
| `metadata` | object | No | Custom data |

**Response:** Same as identity verification.

---

### Get Session Status

Retrieve the current status of a verification session.

```http
GET /v1/sessions/{session_id}
```

**Response (200 OK):**

```json
{
  "session_id": "vs_abc123def456",
  "status": "verified",
  "template_name": "kyc_basic",
  "response_mode": "answers",
  "result": {
    "answers": {
      "given_name": "John",
      "family_name": "Doe",
      "birth_date": "1990-01-15"
    }
  },
  "verified_at": "2026-02-08T10:35:00Z",
  "metadata": {
    "userId": "user_123"
  },
  "created_at": "2026-02-08T10:30:00Z",
  "expires_at": "2026-02-08T10:40:00Z"
}
```

**Status Values:**

| Status | Description |
|--------|-------------|
| `pending` | Waiting for user to verify |
| `verified` | Successfully verified |
| `failed` | Verification failed |
| `expired` | Session timed out |

**Result with raw_credentials mode:**

```json
{
  "session_id": "vs_abc123def456",
  "status": "verified",
  "result": {
    "credentials": [{
      "format": "dc+sd-jwt",
      "vct": "urn:eudi:pid:1",
      "issuer": "https://issuer.example.com",
      "disclosed_claims": {
        "given_name": "John",
        "family_name": "Doe",
        "birth_date": "1990-01-15",
        "nationality": "DE"
      },
      "raw": "eyJ0eXAiOiJ2YytzZC1qd3QiLC..."
    }]
  }
}
```

---

### Cancel Session

Cancel an active verification session.

```http
DELETE /v1/sessions/{session_id}
```

**Response (204 No Content)**

---

### List Templates

List available verification templates.

```http
GET /v1/templates
```

**Response (200 OK):**

```json
{
  "templates": [
    {
      "name": "age_check",
      "display_name": "Age Verification",
      "type": "identity",
      "description": "Verify user is 18 or older",
      "claims": ["age_over_18"],
      "is_system": true
    },
    {
      "name": "kyc_basic",
      "display_name": "Basic KYC",
      "type": "identity",
      "description": "Basic identity verification",
      "claims": ["given_name", "family_name", "birth_date"],
      "is_system": true
    },
    {
      "name": "my_custom_template",
      "display_name": "Custom Verification",
      "type": "identity",
      "description": "Custom template for my use case",
      "claims": ["given_name", "nationality"],
      "is_system": false
    }
  ]
}
```

---

### Create Custom Template

Create a custom verification template.

```http
POST /v1/templates
```

**Request Body:**

```json
{
  "name": "my_verification",
  "display_name": "My Custom Verification",
  "type": "identity",
  "description": "Verify specific claims for my use case",
  "claims": [
    {
      "path": ["given_name"],
      "required": true,
      "alias": "first_name"
    },
    {
      "path": ["family_name"],
      "required": true,
      "alias": "last_name"
    },
    {
      "path": ["address", "country"],
      "required": false,
      "alias": "country"
    }
  ],
  "valid_credentials": ["urn:eudi:pid:1"]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Unique template name (lowercase, underscores) |
| `display_name` | string | No | Human-readable name |
| `type` | string | Yes | `identity` or `payment` |
| `description` | string | No | Template description |
| `claims` | array | Yes | List of claims to request |
| `claims[].path` | array | Yes | JSON path to claim |
| `claims[].required` | boolean | No | Whether claim is required (default: true) |
| `claims[].alias` | string | No | Field name in response |
| `valid_credentials` | array | No | Allowed VCT/doctype values |

**Response (201 Created):**

```json
{
  "name": "my_verification",
  "display_name": "My Custom Verification",
  "type": "identity",
  "created_at": "2026-02-08T10:00:00Z"
}
```

---

### Delete Template

Delete a custom template.

```http
DELETE /v1/templates/{template_name}
```

**Response (204 No Content)**

Note: System templates cannot be deleted.

---

### Create Webhook

Register a webhook endpoint.

```http
POST /v1/webhooks
```

**Request Body:**

```json
{
  "url": "https://example.com/webhooks/verify",
  "events": ["session.verified", "session.failed", "session.expired"],
  "secret": "optional_custom_secret"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `url` | string | Yes | Webhook endpoint URL (HTTPS required) |
| `events` | array | Yes | Events to subscribe to |
| `secret` | string | No | Signing secret (auto-generated if not provided) |

**Response (201 Created):**

```json
{
  "id": "wh_abc123",
  "url": "https://example.com/webhooks/verify",
  "events": ["session.verified", "session.failed", "session.expired"],
  "secret": "whsec_xyz789...",
  "enabled": true,
  "created_at": "2026-02-08T10:00:00Z"
}
```

---

### List Webhooks

List registered webhook endpoints.

```http
GET /v1/webhooks
```

**Response (200 OK):**

```json
{
  "webhooks": [
    {
      "id": "wh_abc123",
      "url": "https://example.com/webhooks/verify",
      "events": ["session.verified", "session.failed", "session.expired"],
      "enabled": true,
      "created_at": "2026-02-08T10:00:00Z",
      "last_used_at": "2026-02-08T15:30:00Z"
    }
  ]
}
```

---

### Delete Webhook

Delete a webhook endpoint.

```http
DELETE /v1/webhooks/{webhook_id}
```

**Response (204 No Content)**

---

### Test Webhook

Send a test event to a webhook endpoint.

```http
POST /v1/webhooks/{webhook_id}/test
```

**Request Body:**

```json
{
  "event": "session.verified"
}
```

**Response (200 OK):**

```json
{
  "success": true,
  "response_status": 200,
  "response_time_ms": 150
}
```

---

## Custom DCQL Queries

For advanced use cases, use DCQL (Digital Credentials Query Language) directly:

```http
POST /v1/verify/identity
```

```json
{
  "dcql": {
    "credentials": [
      {
        "id": "pid_credential",
        "format": "dc+sd-jwt",
        "meta": {
          "vct_values": ["urn:eudi:pid:1"]
        },
        "claims": [
          {
            "path": ["given_name"]
          },
          {
            "path": ["family_name"]
          },
          {
            "path": ["birth_date"],
            "filter": {
              "type": "string",
              "format": "date",
              "maximum": "2008-01-01"
            }
          },
          {
            "path": ["address", "country"],
            "intent_to_retain": false
          }
        ]
      }
    ]
  }
}
```

**DCQL Structure:**

| Field | Description |
|-------|-------------|
| `credentials` | Array of credential requirements |
| `credentials[].id` | Identifier for this credential in the query |
| `credentials[].format` | Credential format (`dc+sd-jwt`, `mso_mdoc`) |
| `credentials[].meta.vct_values` | Allowed VCT values (SD-JWT) |
| `credentials[].meta.doctype_values` | Allowed doctype values (mDoc) |
| `credentials[].claims` | Array of claim requirements |
| `credentials[].claims[].path` | JSON path to claim |
| `credentials[].claims[].filter` | JSON Schema filter for claim value |
| `credentials[].claims[].intent_to_retain` | Data retention intent |

---

## Error Responses

All errors follow a consistent format:

```json
{
  "error": {
    "code": "invalid_template",
    "message": "Template 'unknown' not found",
    "details": {
      "available_templates": ["age_check", "kyc_basic", "kyc_full"]
    }
  }
}
```

**Error Codes:**

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `unauthorized` | 401 | Invalid or missing API key |
| `forbidden` | 403 | Insufficient permissions |
| `invalid_request` | 400 | Malformed request body |
| `invalid_template` | 400 | Template not found |
| `session_not_found` | 404 | Session does not exist |
| `session_expired` | 400 | Session has expired |
| `rate_limited` | 429 | Too many requests |
| `internal_error` | 500 | Server error |

**Rate Limit Response:**

```json
{
  "error": {
    "code": "rate_limited",
    "message": "Rate limit exceeded",
    "details": {
      "retry_after": 60
    }
  }
}
```

---

## Webhook Events

### session.verified

```json
{
  "event": "session.verified",
  "session_id": "vs_abc123",
  "timestamp": "2026-02-08T10:35:00Z",
  "status": "verified",
  "template_name": "kyc_basic",
  "metadata": {
    "userId": "user_123"
  },
  "result": {
    "answers": {
      "given_name": "John",
      "family_name": "Doe"
    }
  }
}
```

### session.failed

```json
{
  "event": "session.failed",
  "session_id": "vs_abc123",
  "timestamp": "2026-02-08T10:35:00Z",
  "status": "failed",
  "template_name": "kyc_basic",
  "metadata": {
    "userId": "user_123"
  },
  "failure_reason": "invalid_credential"
}
```

### session.expired

```json
{
  "event": "session.expired",
  "session_id": "vs_abc123",
  "timestamp": "2026-02-08T10:40:00Z",
  "status": "expired",
  "template_name": "kyc_basic",
  "metadata": {
    "userId": "user_123"
  }
}
```

**Webhook Headers:**

| Header | Description |
|--------|-------------|
| `X-Verify-Signature` | HMAC-SHA256 signature |
| `X-Verify-Timestamp` | Event timestamp |
| `Content-Type` | `application/json` |

See [Webhook Integration](./webhook-integration.md) for signature verification.

---

## Polling Best Practices

When polling for session status:

1. **Start after user scans** - Don't poll immediately
2. **Use reasonable intervals** - 2-3 seconds recommended
3. **Implement timeout** - Stop after 5-10 minutes
4. **Handle all states** - Including expired

**Example Polling Logic:**

```javascript
async function pollSession(sessionId, options = {}) {
  const { timeout = 300000, interval = 2000 } = options;
  const startTime = Date.now();

  while (Date.now() - startTime < timeout) {
    const response = await fetch(`/v1/sessions/${sessionId}`, {
      headers: { 'Authorization': `Bearer ${apiKey}` }
    });

    const session = await response.json();

    if (session.status !== 'pending') {
      return session;
    }

    await new Promise(resolve => setTimeout(resolve, interval));
  }

  throw new Error('Polling timeout');
}
```

---

## SDK vs Direct API

| Feature | SDK | Direct API |
|---------|-----|------------|
| Type safety | Yes | Manual |
| Error handling | Built-in | Manual |
| Polling helpers | Yes | Manual |
| Retry logic | Built-in | Manual |
| Webhook validation | Helper functions | Manual |

For most use cases, the [SDKs](./sdk-integration.md) are recommended.
