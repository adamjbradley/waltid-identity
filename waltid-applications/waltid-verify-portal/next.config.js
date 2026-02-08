/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  env: {
    NEXT_PUBLIC_VERIFY_API_URL: process.env.NEXT_PUBLIC_VERIFY_API_URL || 'http://localhost:7005',
  },
};

module.exports = nextConfig;
