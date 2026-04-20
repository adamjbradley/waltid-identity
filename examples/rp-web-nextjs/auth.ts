import NextAuth from 'next-auth';
import Keycloak from 'next-auth/providers/keycloak';

// Two OIDC providers are configured side-by-side so the user can pick
// either one from the landing page:
//   - id "keycloak" → Keycloak directly (classic OIDC)
//   - id "authop"   → auth-op (realm discovery: OIDC OR verifiable
//                     presentation as login)
// Both use the generic Keycloak provider (auth-op advertises a compatible
// OIDC discovery doc). Each provider id owns its own callback path:
// /api/auth/callback/keycloak and /api/auth/callback/authop.
export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({
      id: 'keycloak',
      name: 'Keycloak',
      clientId: process.env.AUTH_KEYCLOAK_ID!,
      clientSecret: process.env.AUTH_KEYCLOAK_SECRET!,
      issuer: process.env.AUTH_KEYCLOAK_ISSUER!,
    }),
    Keycloak({
      id: 'authop',
      name: 'auth-op',
      clientId: process.env.AUTH_AUTHOP_ID!,
      clientSecret: process.env.AUTH_AUTHOP_SECRET!,
      issuer: process.env.AUTH_AUTHOP_ISSUER!,
    }),
  ],
  trustHost: true,
  session: { strategy: 'jwt' },
  callbacks: {
    async jwt({ token, account, profile }) {
      if (account) {
        token.idToken = account.id_token;
        token.provider = account.provider;
      }
      if (profile) {
        token.name = (profile as { name?: string; preferred_username?: string }).name
          ?? (profile as { preferred_username?: string }).preferred_username
          ?? token.name;
      }
      return token;
    },
    async session({ session, token }) {
      const s = session as { idToken?: string; provider?: string };
      s.idToken = token.idToken as string | undefined;
      s.provider = token.provider as string | undefined;
      return session;
    },
  },
});
