const nextConfig = {
  reactStrictMode: false,
  output: 'standalone',
  publicRuntimeConfig: {
    NEXT_PUBLIC_VC_REPO: process.env.NEXT_PUBLIC_VC_REPO ?? "https://credentials.walt.id/",
    NEXT_PUBLIC_ISSUER: process.env.NEXT_PUBLIC_ISSUER ?? "https://issuer.portal.walt.id",
    NEXT_PUBLIC_VERIFIER: process.env.NEXT_PUBLIC_VERIFIER ?? "https://verifier.portal.walt.id",
    NEXT_PUBLIC_VERIFIER2: process.env.NEXT_PUBLIC_VERIFIER2 ?? "",
    NEXT_PUBLIC_VERIFIER2_CLIENT_ID: process.env.NEXT_PUBLIC_VERIFIER2_CLIENT_ID ?? "",
    NEXT_PUBLIC_VERIFIER2_SIGNING_KEY: process.env.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY ?? "",
    NEXT_PUBLIC_VERIFIER2_X5C: process.env.NEXT_PUBLIC_VERIFIER2_X5C ?? "",
    NEXT_PUBLIC_WALLET: process.env.NEXT_PUBLIC_WALLET ?? "https://wallet.walt.id"
  },
}

module.exports = nextConfig
