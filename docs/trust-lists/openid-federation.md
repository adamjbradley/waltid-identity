# OpenID Federation

OpenID Federation enables trust establishment through hierarchical entity statements, allowing verifiers and wallets to validate issuers by walking trust chains up to configured trust anchors. This is one of two trust sources supported by the trust lists feature (alongside ETSI Trusted Lists).

> **IMPORTANT: OpenID Federation is DISABLED by default.** This feature requires explicit opt-in via `OPENID_FEDERATION_ENABLED=true` AND at least one configured trust anchor. It has zero impact on existing flows when disabled.

## Overview

OpenID Federation resolves trust by:
1. Fetching the target entity's self-signed entity statement (from `/.well-known/openid-federation`)
2. Following `authority_hints` to discover intermediate entities
3. Walking up the chain until reaching a configured trust anchor
4. Validating that the chain is complete and within depth limits

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Trust Anchor    │◀────│  Intermediate   │◀────│  Leaf Entity    │
│  (configured)    │     │  (auto-found)   │     │  (issuer/verifier)│
└─────────────────┘     └─────────────────┘     └─────────────────┘
        ▲                                               │
        └───────────────── trust chain ─────────────────┘
```

## Default State

| Location | Default Value | Purpose |
|----------|---------------|---------|
| `trust-lists.conf` | `enabled = false` | Base configuration |
| `docker-compose/.env` | `OPENID_FEDERATION_ENABLED=false` | Docker environment |
| `trust-lists.conf` | `trustAnchors = []` | No trust anchors configured |

When disabled:
- Federation service is NOT instantiated (lazy initialization)
- Trust chain resolution is NOT attempted during validation
- Zero impact on ETSI trust list validation
- Status endpoint reports federation as disabled

## Quick Start

### 1. Enable the Feature

Both `TRUST_LISTS_ENABLED` and `OPENID_FEDERATION_ENABLED` must be `true`:

```bash
# In docker-compose/.env or .env.local
TRUST_LISTS_ENABLED=true
OPENID_FEDERATION_ENABLED=true
```

### 2. Configure Trust Anchors

Edit the `trust-lists.conf` for your service:

```hocon
openidFederation {
    enabled = true
    trustAnchors = [
        "https://trust-anchor.example.com"
    ]
}
```

Or set via environment variable override in HOCON:
```hocon
enabled = ${?OPENID_FEDERATION_ENABLED}
```

### 3. Restart the Service

```bash
docker compose --profile identity up -d verifier-api2
```

### 4. Verify It's Enabled

```bash
curl http://localhost:7004/admin/trust/status | jq '.sources.OPENID_FEDERATION'
```

Expected output:
```json
{
  "enabled": true,
  "healthy": false,
  "entryCount": 1
}
```

Note: `healthy` becomes `true` after the first successful trust chain resolution.

## Configuration Reference

### trust-lists.conf — openidFederation block

```hocon
openidFederation {
    # Master switch — overridable via OPENID_FEDERATION_ENABLED env var
    enabled = false
    enabled = ${?OPENID_FEDERATION_ENABLED}

    # Trust anchor entity IDs (federation roots)
    # These are the entities at the top of trust chains
    trustAnchors = []
    # Example:
    # trustAnchors = [
    #     "https://trust-anchor.example.com",
    #     "https://federation.eudi.ec.europa.eu"
    # ]

    # Maximum trust chain depth (default: 5)
    # Limits how many hops from leaf entity to trust anchor
    maxChainDepth = 5

    # Cache TTL in seconds for entity statements (default: 3600 = 1 hour)
    # Entity statements are cached to avoid repeated HTTP fetches
    cacheTtlSeconds = 3600
}
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TRUST_LISTS_ENABLED` | `false` | Master switch for all trust list features |
| `OPENID_FEDERATION_ENABLED` | `false` | Enable OpenID Federation trust source |
| `ETSI_LOTL_URL` | EU LOTL URL | ETSI List of Trusted Lists URL |

## How Trust Chain Resolution Works

When validating an issuer or verifier, the federation service:

1. **Fetch entity statement**: HTTP GET `{entityId}/.well-known/openid-federation`
2. **Parse JWT**: Decode the JWT response (note: JWT signature verification is not yet implemented)
3. **Check trust anchors**: If the entity is a configured trust anchor, chain is complete
4. **Follow authority hints**: The entity statement contains `authority_hints` — a list of superior entities
5. **Fetch subordinate statement**: For each authority hint, fetch `{authority}/.well-known/openid-federation?sub={entity}`
6. **Recurse**: Repeat steps 3-5 for each authority until a trust anchor is reached or `maxChainDepth` is exceeded

### Result

The `TrustChain` result contains:
- `valid: Boolean` — whether the chain reaches a configured trust anchor
- `trustAnchorId: String` — the trust anchor at the root of the chain
- `depth: Int` — number of entity statements in the chain
- `statements: List<EntityStatement>` — the full chain of entity statements

## Integration with Composite Trust Service

OpenID Federation is one of two trust sources in the `CompositeTrustService`. The validation order is:

1. **ETSI Trusted Lists** — checked first (EU-mandated trust infrastructure)
2. **OpenID Federation** — checked second (standards-based federation)

If either source returns a trusted result, validation succeeds. Both sources operate independently and can be enabled/disabled separately.

## Security Considerations

1. **JWT signature verification is NOT yet implemented** — entity statement JWTs are decoded but their signatures are not verified. This means the system trusts the content of entity statements without cryptographic proof. This is suitable for development and testing but should be addressed before production use.
2. **Trust anchors must be carefully curated** — only add trust anchors you explicitly trust, as any entity with a valid chain to a trust anchor will be considered trusted.
3. **Chain depth limits** — the `maxChainDepth` setting prevents infinite loops in misconfigured federations.
4. **Caching** — entity statements are cached for `cacheTtlSeconds` to reduce network load, but this means revocations may take up to one cache cycle to take effect.

## Related Documentation

- [OpenID Federation 1.0 Specification](https://openid.net/specs/openid-federation-1_0.html)
- [EUDI Architecture Reference Framework](https://github.com/eu-digital-identity-wallet/eudi-doc-architecture-and-reference-framework)
- [Trust Lists Overview](README.md)
