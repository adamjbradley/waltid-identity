/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',
  env: {
    NEXT_PUBLIC_WEB_WALLET_URL: process.env.NEXT_PUBLIC_WEB_WALLET_URL || 'https://wallet.theaustraliahack.com',
  },
}

module.exports = nextConfig
