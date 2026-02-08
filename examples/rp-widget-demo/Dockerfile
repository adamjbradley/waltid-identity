# Simple Express server for Widget SDK demo
FROM node:20-alpine

WORKDIR /app

# Copy package files
COPY package.json package-lock.json* ./

# Install dependencies
RUN npm ci --only=production

# Copy source code
COPY . .

# Create non-root user
RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 demoapp
RUN chown -R demoapp:nodejs /app

USER demoapp

EXPOSE 3002

ENV PORT=3002
ENV NODE_ENV=production

CMD ["node", "server.js"]
