import Link from 'next/link';
import { auth, signIn, signOut } from '@/auth';

export default async function LandingPage() {
  const session = await auth();

  async function doSignIn() {
    'use server';
    await signIn('keycloak', { redirectTo: '/login' });
  }

  async function doSignOut() {
    'use server';
    await signOut({ redirectTo: '/' });
  }

  return (
    <main className="min-h-screen p-8 bg-gray-50">
      <div className="max-w-5xl mx-auto">
        <div className="text-center mb-10">
          <h1 className="text-4xl font-bold text-gray-900 mb-2">
            rp.theaustraliahack.com
          </h1>
          <p className="text-gray-600">
            Demo of two independent identity paths &mdash; pick one.
          </p>
        </div>

        <div className="grid md:grid-cols-2 gap-6">
          {/* OIDC via Keycloak */}
          <section className="bg-white rounded-xl shadow-md p-8 flex flex-col">
            <div className="text-center mb-4">
              <span className="text-5xl">🔑</span>
            </div>
            <h2 className="text-2xl font-semibold text-center mb-2">Sign in with Keycloak</h2>
            <p className="text-gray-600 text-sm text-center flex-1">
              Classic OpenID Connect Relying Party flow. Authenticate against the
              project&apos;s Keycloak server to establish a user session.
            </p>

            {session?.user ? (
              <div className="mt-6 border-t pt-4 text-center">
                <p className="text-sm text-gray-500">Signed in as</p>
                <p className="font-medium text-gray-900 mb-4">
                  {session.user.email || session.user.name}
                </p>
                <div className="flex gap-2 justify-center">
                  <Link
                    href="/login"
                    className="bg-blue-600 hover:bg-blue-700 text-white font-semibold px-5 py-2 rounded-lg transition-colors text-sm"
                  >
                    Account
                  </Link>
                  <form action={doSignOut}>
                    <button
                      type="submit"
                      className="bg-gray-200 hover:bg-gray-300 text-gray-800 font-semibold px-5 py-2 rounded-lg transition-colors text-sm"
                    >
                      Sign out
                    </button>
                  </form>
                </div>
              </div>
            ) : (
              <form action={doSignIn} className="mt-6 text-center">
                <button
                  type="submit"
                  className="bg-blue-600 hover:bg-blue-700 text-white font-semibold px-8 py-3 rounded-lg transition-colors"
                >
                  Sign in with Keycloak
                </button>
              </form>
            )}
          </section>

          {/* Wallet-based verification */}
          <section className="bg-white rounded-xl shadow-md p-8 flex flex-col">
            <div className="text-center mb-4">
              <span className="text-5xl">🪪</span>
            </div>
            <h2 className="text-2xl font-semibold text-center mb-2">Verify with wallet</h2>
            <p className="text-gray-600 text-sm text-center flex-1">
              Present a verifiable credential from your digital wallet to prove
              your age for an age-restricted checkout &mdash; no account needed.
            </p>
            <div className="mt-6 text-center">
              <Link
                href="/checkout"
                className="inline-block bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-8 py-3 rounded-lg transition-colors"
              >
                Start age verification
              </Link>
            </div>
          </section>
        </div>

        <div className="text-center mt-10 text-xs text-gray-400">
          <p>walt.id &middot; Verify API &middot; Keycloak OIDC</p>
        </div>
      </div>
    </main>
  );
}
