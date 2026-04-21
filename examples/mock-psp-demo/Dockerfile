# Mock PSP (Bank of Demo) — Payment Wallet Attestation issuer service.
# Shape mirrors examples/rp-widget-demo/Dockerfile exactly so ops behaviour
# (user id, work dir, restart semantics) is interchangeable between the two.
FROM node:20-alpine

WORKDIR /app

# Copy package files
COPY package.json package-lock.json* ./

# Install dependencies
RUN npm ci --only=production || npm install --only=production

# Copy source code
COPY . .

# Create non-root user
RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 pspapp
RUN chown -R pspapp:nodejs /app

USER pspapp

EXPOSE 7006

ENV PORT=7006
ENV NODE_ENV=production

CMD ["node", "server.js"]
