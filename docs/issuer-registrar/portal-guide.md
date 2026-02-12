# Issuer Admin Portal Guide

## Overview

The Issuer Admin page (`/admin/issuers`) provides a web-based management interface for multi-tenant credential issuer registration.

**Prerequisite:** `ISSUER_REGISTRAR_ENABLED=true`

## Accessing the Portal

Navigate to `http://localhost:7102/admin/issuers`.

Admin navigation: Portal (`/admin`), Trust Lists (`/admin/trust-config`), Issuers (`/admin/issuers`), Relying Parties (`/admin/relying-parties`).

## Tenant Management

### Registering a New Issuer

| Field | Required | Description |
|-------|----------|-------------|
| Legal Name | Yes | Full legal name of the issuing organization |
| Country Code | Yes | ISO 3166-1 alpha-2 code (e.g., `AU`, `DE`) |
| Domain | Yes | Domain name of the issuer |
| Contact Email | Yes | Administrative contact email |
| Address | No | Physical address |

### Status Management

| Status | Description | Transitions |
|--------|-------------|-------------|
| **ACTIVE** | Operational, in trust lists | Suspend, Revoke |
| **SUSPENDED** | Temporarily disabled | Activate, Revoke |
| **REVOKED** | Permanently disabled | None |

## Certificate Management

Click **Generate Certificate** to create IACA root + Document Signer certificates (ECDSA P-256).

## Credential Configuration

### Template Picker

| Category | Templates |
|----------|-----------|
| **EUDI** | PID (mDoc), PID (SD-JWT), mDL, EHIC, PhotoID |
| **Financial** | Payment Wallet Attestation, Bank Account, Credit Score |
| **Identity** | Employee ID, Student ID, Membership Card, Age Verification |

### JSON Editor

Direct editing of credential configuration JSON with syntax validation.

## Quick Actions

| Action | Description |
|--------|-------------|
| **Issue Credential** | Navigate to issuance with this issuer pre-selected |
| **View Metadata** | Open OpenID Credential Issuer metadata |
| **Copy Country TSL URL** | Copy trust list URL to clipboard |

## Trust List URLs

| Endpoint | URL |
|----------|-----|
| **LOTL** | `{ISSUER_API}/admin/issuer/lotl.xml` |
| **Country TSL** | `{ISSUER_API}/admin/issuer/tsl/{CC}.xml` |

See [cross-border-trust.md](cross-border-trust.md) for integration.
