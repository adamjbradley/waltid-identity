/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  env: {
    VERIFY_API_URL: process.env.VERIFY_API_URL || 'http://localhost:7005',
  },
};

module.exports = nextConfig;
