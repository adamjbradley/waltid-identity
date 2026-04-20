import Link from 'next/link';
import { auth, signIn, signOut } from '@/auth';

export default async function LandingPage() {
  const session = await auth();
  const provider = (session as { provider?: string } | null)?.provider;

  async function signInKeycloak() {
    'use server';
    await signIn('keycloak', { redirectTo: '/login' });
  }

  async function signInAuthOp() {
    'use server';
    await signIn('authop', { redirectTo: '/login' });
  }

  async function doSignOut() {
    'use server';
    await signOut({ redirectTo: '/' });
  }

  return (
    <main className="min-h-screen p-8 bg-gray-50">
      <div className="max-w-6xl mx-auto">
        <div className="text-center mb-10">
          <h1 className="text-4xl font-bold text-gray-900 mb-2">
            rp.theaustraliahack.com
          </h1>
          <p className="text-gray-600">
            Demo of three independent identity paths &mdash; pick one.
          </p>
        </div>

        {session?.user && (
          <div className="bg-green-50 border border-green-200 rounded-xl p-4 mb-6 text-center">
            <p className="text-sm text-green-700">
              Signed in as <strong>{session.user.email || session.user.name}</strong>
              {provider && <> via <code className="bg-green-100 px-1.5 py-0.5 rounded">{provider}</code></>}
            </p>
            <div className="flex gap-2 justify-center mt-2">
              <Link
                href="/login"
                className="bg-blue-600 hover:bg-blue-700 text-white font-semibold px-4 py-1.5 rounded-lg transition-colors text-sm"
              >
                Account
              </Link>
              <form action={doSignOut}>
                <button
                  type="submit"
                  className="bg-gray-200 hover:bg-gray-300 text-gray-800 font-semibold px-4 py-1.5 rounded-lg transition-colors text-sm"
                >
                  Sign out
                </button>
              </form>
            </div>
          </div>
        )}

        <div className="grid md:grid-cols-3 gap-6">
          {/* OIDC via Keycloak directly */}
          <section className="bg-white rounded-xl shadow-md p-6 flex flex-col">
            <div className="text-center mb-3">
              <span className="text-5xl">🔑</span>
            </div>
            <h2 className="text-xl font-semibold text-center mb-2">Sign in with Keycloak</h2>
            <p className="text-gray-600 text-sm text-center flex-1">
              Classic OpenID Connect against the project&apos;s Keycloak server.
              Username + password in the realm store.
            </p>
            <form action={signInKeycloak} className="mt-5 text-center">
              <button
                type="submit"
                className="w-full bg-blue-600 hover:bg-blue-700 text-white font-semibold px-6 py-2.5 rounded-lg transition-colors"
              >
                Sign in with Keycloak
              </button>
            </form>
          </section>

          {/* OIDC via auth-op broker — password OR VC */}
          <section className="bg-white rounded-xl shadow-md p-6 flex flex-col border-2 border-purple-200">
            <div className="text-center mb-3">
              <span className="text-5xl">🎫</span>
            </div>
            <h2 className="text-xl font-semibold text-center mb-2">Sign in via auth-op</h2>
            <p className="text-gray-600 text-sm text-center flex-1">
              OIDC broker that offers a realm picker. Pick <em>employees</em>
              {' '}(delegates to Keycloak) or <em>citizens</em> (present a
              verifiable credential from your wallet — no password).
            </p>
            <form action={signInAuthOp} className="mt-5 text-center">
              <button
                type="submit"
                className="w-full bg-purple-600 hover:bg-purple-700 text-white font-semibold px-6 py-2.5 rounded-lg transition-colors"
              >
                Sign in via auth-op
              </button>
            </form>
          </section>

          {/* Wallet-based verification (no login, just a verifiable claim) */}
          <section className="bg-white rounded-xl shadow-md p-6 flex flex-col">
            <div className="text-center mb-3">
              <span className="text-5xl">🪪</span>
            </div>
            <h2 className="text-xl font-semibold text-center mb-2">Verify with wallet</h2>
            <p className="text-gray-600 text-sm text-center flex-1">
              Present a verifiable credential to prove your age for an
              age-restricted checkout &mdash; no account needed.
            </p>
            <div className="mt-5 text-center">
              <Link
                href="/checkout"
                className="block w-full bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-6 py-2.5 rounded-lg transition-colors"
              >
                Start age verification
              </Link>
            </div>
          </section>
        </div>

        <div className="text-center mt-10 text-xs text-gray-400">
          <p>walt.id &middot; Verify API &middot; Keycloak &middot; auth-op</p>
        </div>
      </div>
    </main>
  );
}
