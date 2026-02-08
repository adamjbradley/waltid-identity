# Verify API Example - E-commerce Age Verification

This example Next.js application demonstrates how to integrate with the walt.id Verify API
for age verification in an e-commerce checkout flow.

## Features

- **QR Code Verification**: Cross-device flow with QR code scanning
- **Same-Device Flow**: Deep link support for mobile wallets
- **Polling Status Updates**: Real-time verification status via polling
- **Webhook Support**: Receive verification results via webhooks
- **Responsive Design**: Works on desktop and mobile

## Quick Start

### Prerequisites

- Node.js 18+ installed
- Verify API running (default: http://localhost:7010)
- Valid Verify API key

### Installation

```bash
# Install dependencies
npm install

# Copy environment template
cp .env.example .env.local

# Edit .env.local with your configuration
```

### Configuration

Edit `.env.local`:

```bash
# Verify API Configuration
VERIFY_API_URL=http://localhost:7010
VERIFY_API_KEY=vfy_your_api_key_here

# Webhook Configuration (optional)
WEBHOOK_URL=http://localhost:3001/api/webhooks
WEBHOOK_SECRET=your-webhook-secret
```

### Running

```bash
# Development mode
npm run dev

# Production build
npm run build
npm start
```

The application runs on http://localhost:3001 by default.

## How It Works

### Verification Flow

1. **User clicks "Verify Age"**
   - Frontend calls `/api/verify` with the desired template
   - Backend creates a verification session via Verify API

2. **QR Code / Deep Link**
   - Desktop: QR code is displayed for scanning with wallet app
   - Mobile: Deep link button opens the wallet app directly

3. **Wallet Interaction**
   - User scans QR or clicks deep link
   - Wallet app prompts user to share credentials
   - User approves sharing age verification claim

4. **Result Delivery**
   - Option A: Frontend polls `/api/sessions/{id}` for status
   - Option B: Verify API sends webhook to `/api/webhooks`

5. **Completion**
   - On success: User can proceed to checkout
   - On failure: User can retry verification

### API Routes

#### POST /api/verify

Starts a new verification session.

**Request:**
```json
{
  "template": "age_check"
}
```

**Response:**
```json
{
  "sessionId": "sess_xxx",
  "qrCodeData": "openid4vp://...",
  "deepLink": "openid4vp://...",
  "expiresAt": "2024-01-15T12:00:00Z"
}
```

#### GET /api/sessions/[id]

Gets the current status of a verification session.

**Response:**
```json
{
  "sessionId": "sess_xxx",
  "status": "verified",
  "result": {
    "claims": {
      "ageOver18": true,
      "ageOver21": true
    }
  }
}
```

#### POST /api/webhooks

Receives webhook events from Verify API.

**Events:**
- `session.verified` - Verification completed successfully
- `session.failed` - Verification failed
- `session.expired` - Session expired without completion

## Project Structure

```
rp-web-nextjs/
├── app/
│   ├── api/
│   │   ├── verify/
│   │   │   └── route.ts      # Start verification endpoint
│   │   ├── sessions/
│   │   │   └── [id]/
│   │   │       └── route.ts  # Session status endpoint
│   │   └── webhooks/
│   │       └── route.ts      # Webhook receiver
│   ├── globals.css           # Tailwind CSS styles
│   ├── layout.tsx            # App layout
│   └── page.tsx              # Main checkout page
├── .env.example              # Environment template
├── package.json
├── tailwind.config.js
└── tsconfig.json
```

## Customization

### Using Different Templates

The example uses `age_check` template. You can use other templates:

```typescript
// In page.tsx
body: JSON.stringify({ template: 'kyc_basic' })

// Or for identity verification
body: JSON.stringify({ template: 'identity_full' })
```

### Styling

The app uses Tailwind CSS. Modify `tailwind.config.js` and styles in
`app/globals.css` to match your brand.

### Webhook Integration

For production, configure webhooks to update your database when
verification completes. See `/api/webhooks/route.ts` for the handler
structure.

## Production Deployment

### Environment Variables

Set these in your deployment platform:

| Variable | Required | Description |
|----------|----------|-------------|
| `VERIFY_API_URL` | Yes | Verify API base URL |
| `VERIFY_API_KEY` | Yes | Your API key (use production key) |
| `WEBHOOK_URL` | No | Public URL for webhook delivery |
| `WEBHOOK_SECRET` | No | Secret for webhook signature verification |

### Vercel Deployment

```bash
npm i -g vercel
vercel
```

### Docker Deployment

```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY . .
RUN npm run build
EXPOSE 3001
CMD ["npm", "start"]
```

## Security Considerations

1. **API Key**: Never expose your API key to the frontend. All Verify API
   calls should go through your backend.

2. **Webhook Verification**: Always verify webhook signatures in production
   using the `WEBHOOK_SECRET`.

3. **Session Handling**: Don't trust frontend-reported verification status.
   Always verify through the API or webhooks.

4. **HTTPS**: Use HTTPS in production for all endpoints.

## Testing

This example includes comprehensive Playwright E2E tests.

### Running Tests

```bash
# Install Playwright browsers (first time only)
npx playwright install

# Run tests (headless)
npm test

# Run tests with UI
npm run test:ui

# Run tests in headed mode (visible browser)
npm run test:headed

# Debug tests
npm run test:debug
```

### Test Coverage

The Playwright tests cover:

| Test Suite | Tests | Description |
|------------|-------|-------------|
| Checkout Page | 4 | Page loads, product display, idle state |
| Loading State | 1 | Loading spinner display |
| QR Code Display | 5 | QR code rendering, deep link, cancel flow |
| Mobile Flow | 1 | Same-device wallet button |
| Success Flow | 2 | Verified state, start over option |
| Failure Flow | 3 | Failed state, expired state, retry flow |
| API Error Handling | 2 | Server errors, network errors |
| Accessibility | 3 | Headings, focus, visual indicators |

**Total: 21 tests**

### Test Configuration

Tests are configured in `playwright.config.ts`:
- Runs against `http://localhost:3000` (dev server auto-started)
- Tests Chromium, Firefox, and WebKit browsers
- Mobile viewport tests for responsive design
- API mocking for consistent test behavior

## Troubleshooting

### "Failed to start verification"

- Check that Verify API is running at the configured URL
- Verify your API key is valid
- Check network connectivity

### QR Code Not Scanning

- Ensure the wallet app supports OpenID4VP
- Check that the QR code URL scheme is correct
- Try the deep link as an alternative

### Webhook Not Received

- Verify webhook URL is publicly accessible
- Check firewall rules
- Review Verify API logs for delivery attempts

## Support

For issues with this example, please open an issue on the repository.

For Verify API documentation, see the main project README.
