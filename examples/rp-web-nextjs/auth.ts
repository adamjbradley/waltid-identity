import NextAuth from 'next-auth';
import Keycloak from 'next-auth/providers/keycloak';

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({
      clientId: process.env.AUTH_KEYCLOAK_ID!,
      clientSecret: process.env.AUTH_KEYCLOAK_SECRET!,
      issuer: process.env.AUTH_KEYCLOAK_ISSUER!,
    }),
  ],
  trustHost: true,
  session: { strategy: 'jwt' },
  callbacks: {
    async jwt({ token, account, profile }) {
      if (account) {
        token.idToken = account.id_token;
      }
      if (profile) {
        token.name = (profile as { name?: string; preferred_username?: string }).name
          ?? (profile as { preferred_username?: string }).preferred_username
          ?? token.name;
      }
      return token;
    },
    async session({ session, token }) {
      (session as { idToken?: string }).idToken = token.idToken as string | undefined;
      return session;
    },
  },
});
