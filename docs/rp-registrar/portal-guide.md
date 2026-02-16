# RP Admin Portal Guide

## Overview

The RP Admin page (`/admin/relying-parties`) provides management for multi-tenant relying party registration.

**Prerequisite:** `RP_REGISTRAR_ENABLED=true`

## Registering a New RP

| Field | Required | Description |
|-------|----------|-------------|
| Legal Name | Yes | Full legal name |
| Country Code | Yes | ISO 3166-1 alpha-2 code |
| Domain | Yes | Domain name (used for X.509 SAN and client ID) |
| Contact Email | Yes | Administrative contact |
| Data Protection Officer | Yes | DPO name |
| Privacy Policy URL | Yes | Privacy policy URL |
| Legal Basis | Yes | Legal basis for data processing |

## Status Management

| Status | Badge | Description |
|--------|-------|-------------|
| **PENDING** | Yellow | Awaiting certificate |
| **ACTIVE** | Green | Operational |
| **SUSPENDED** | Red | Temporarily disabled |

## Certificate Management

Click **Generate Certificate** to create an X.509 certificate with:
- `CN={legalName}, C={country}`
- SAN `DNS:{domain}`
- ECDSA P-256, EKU Client Authentication

Click **Download Certificate** for PEM format.

## Quick Actions

| Action | Description |
|--------|-------------|
| **Verify as this RP** | Navigate to verification with RP pre-selected |
| **Copy Verify Link** | Copy verification URL with RP ID parameter |
| **Download Certificate** | Download X.509 certificate (PEM) |

## Verification Flow Integration

When enabled, the verification setup page shows an RP dropdown:

1. Select an RP from "Verifying as" dropdown
2. Only `ACTIVE` RPs with certificates shown
3. "Default verifier (no RP)" uses the default certificate
4. Selected RP ID passed as `rpId` query parameter

## Deleting an RP

Click **Delete** with confirmation. Removes registration, certificate, and invalidates active sessions.
