# WaltVerify Widget SDK Demo

This example demonstrates the WaltVerify Widget SDK for frontend-only identity verification integration.

## Overview

The Widget SDK enables simple, frontend-focused verification flows without requiring deep backend integration. The demo showcases:

- **Modal Mode**: Popup verification with QR code
- **Inline Mode**: Embedded QR code in your page
- **Theme Customization**: Match your brand colors
- **Age Verification**: Simple API for age checks

## Features

- No build step required (plain HTML/JavaScript)
- Simple e-commerce style demo page
- Live theme customization preview
- Code examples for integration

## Quick Start

### Prerequisites

- Node.js 18+
- Verify API running (default: http://localhost:7010)
- Valid API key

### Installation

```bash
# Install dependencies
npm install

# Set environment variables (optional)
export VERIFY_API_URL=http://localhost:7010
export VERIFY_API_KEY=vfy_your_api_key_here

# Start the demo server
npm start
```

The demo runs at http://localhost:3002

### Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3002` | Demo server port |
| `VERIFY_API_URL` | `http://localhost:7010` | Verify API base URL |
| `VERIFY_API_KEY` | `vfy_test_sandbox_demo_key_12345678` | Sandbox demo key (works immediately) |

The default sandbox credentials work immediately without any setup. See [Sandbox Credentials](../../docs/verify-api/sandbox-credentials.md) for details.

## How It Works

### Architecture

```
Browser                     Demo Server              Verify API
   |                             |                        |
   |  GET /                      |                        |
   |<----------------------------|                        |
   |  (HTML page)                |                        |
   |                             |                        |
   |  GET /api/token             |                        |
   |----------------------------->                        |
   |                             |  POST /v1/widget/tokens|
   |                             |----------------------->|
   |                             |<-----------------------|
   |<----------------------------|  (client token)       |
   |  { clientToken: ct_xxx }    |                        |
   |                             |                        |
   |  Load SDK from API          |                        |
   |------------------------------------------------->    |
   |<-------------------------------------------------    |
   |  (sdk.js)                   |                        |
   |                             |                        |
   |  WaltVerify.init()          |                        |
   |  WaltVerify.verifyAge()     |                        |
   |------------------------------------------------->    |
   |  (verification request)     |                        |
```

### Security Model

1. **API Key stays on server**: The backend generates short-lived client tokens
2. **Client token in browser**: Safe to expose, limited scope and lifetime
3. **Origin validation**: Tokens can be scoped to specific domains

## Integration Guide

### Step 1: Include the SDK

```html
<script src="https://your-verify-api/widget/v1/sdk.js"></script>
```

### Step 2: Get a Client Token (Server-side)

Your backend should call the Verify API to get a client token:

```javascript
// Express.js example
app.get('/api/token', async (req, res) => {
  const response = await fetch(`${VERIFY_API_URL}/v1/widget/tokens`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${API_KEY}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      expires_in: 900  // 15 minutes
    })
  });

  const data = await response.json();
  res.json({ clientToken: data.client_token });
});
```

### Step 3: Initialize and Verify

```javascript
// Get token from your backend
const response = await fetch('/api/token');
const { clientToken } = await response.json();

// Initialize SDK
WaltVerify.init({
  clientToken: clientToken,
  theme: {
    primaryColor: '#2563eb'
  }
});

// Age verification (modal)
WaltVerify.verifyAge({
  minAge: 18,
  onSuccess: (result) => console.log('Verified!', result),
  onFailure: (error) => console.log('Failed', error)
});

// Custom template (inline)
WaltVerify.verify({
  template: 'kyc_basic',
  ui: 'inline',
  container: '#verification-div'
});
```

## SDK API Reference

### `WaltVerify.init(options)`

Initialize the SDK with configuration.

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| `clientToken` | string | Yes | Client token from your backend |
| `apiBase` | string | No | API base URL (auto-detected) |
| `theme` | object | No | Theme customization |

Theme options:
- `primaryColor` - Button and accent color
- `backgroundColor` - Modal background
- `textColor` - Text color
- `borderRadius` - Corner radius
- `fontFamily` - Font family

### `WaltVerify.verifyAge(options)`

Start age verification flow.

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| `minAge` | number | Yes | Minimum age required |
| `ui` | string | No | 'modal' (default) or 'inline' |
| `container` | string | No | CSS selector for inline mode |
| `onSuccess` | function | No | Success callback |
| `onFailure` | function | No | Failure callback |
| `onCancel` | function | No | Cancel callback |

### `WaltVerify.verify(options)`

Start custom template verification.

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| `template` | string | Yes | Template name |
| `ui` | string | No | 'modal' (default) or 'inline' |
| `container` | string | No | CSS selector for inline mode |
| `onSuccess` | function | No | Success callback |
| `onFailure` | function | No | Failure callback |
| `onCancel` | function | No | Cancel callback |

### `WaltVerify.close()`

Close any open modal.

### `WaltVerify.isInitialized()`

Returns `true` if SDK is initialized.

## Demo Structure

```
rp-widget-demo/
├── server.js           # Express server for tokens
├── package.json        # Dependencies
├── README.md           # This file
└── public/
    └── index.html      # Demo page (no build required)
```

## Troubleshooting

### "SDK Error: Failed to load"

- Verify the Verify API is running at the configured URL
- Check browser console for CORS errors
- Ensure the SDK endpoint `/widget/v1/sdk.js` is accessible

### "Failed to get token"

- Check your API key is valid
- Verify the API URL is correct
- Check server logs for errors

### Modal not appearing

- Ensure SDK is initialized before calling verify methods
- Check browser console for errors
- Verify client token is not expired

## Related Examples

- **rp-web-nextjs**: Full Next.js integration with backend SDK
- **rp-android**: Android native SDK integration
- **rp-ios**: iOS native SDK integration

## License

Apache-2.0
