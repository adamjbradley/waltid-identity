import Link from 'next/link';
import { redirect } from 'next/navigation';
import { auth, signOut } from '@/auth';

export default async function LoginPage() {
  const session = await auth();
  if (!session?.user) {
    redirect('/api/auth/signin?callbackUrl=/login');
  }

  async function doSignOut() {
    'use server';
    await signOut({ redirectTo: '/' });
  }

  const user = session.user;

  return (
    <main className="min-h-screen p-8 bg-gray-50">
      <div className="max-w-2xl mx-auto">
        <div className="bg-white rounded-xl shadow-md p-8">
          <div className="text-center mb-6">
            <span className="text-5xl">✅</span>
            <h1 className="text-3xl font-bold mt-3">Signed in</h1>
            <p className="text-gray-600 mt-1">
              Authenticated via Keycloak OIDC.
            </p>
          </div>

          <dl className="grid grid-cols-[auto,1fr] gap-x-6 gap-y-2 mb-6 text-sm">
            {user.name && (
              <>
                <dt className="font-medium text-gray-500">Name</dt>
                <dd className="text-gray-900">{user.name}</dd>
              </>
            )}
            {user.email && (
              <>
                <dt className="font-medium text-gray-500">Email</dt>
                <dd className="text-gray-900">{user.email}</dd>
              </>
            )}
          </dl>

          <div className="flex gap-3 justify-center border-t pt-6">
            <Link
              href="/"
              className="bg-gray-200 hover:bg-gray-300 text-gray-800 font-semibold px-5 py-2 rounded-lg transition-colors"
            >
              Home
            </Link>
            <Link
              href="/checkout"
              className="bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-5 py-2 rounded-lg transition-colors"
            >
              Try wallet verification
            </Link>
            <form action={doSignOut}>
              <button
                type="submit"
                className="bg-red-600 hover:bg-red-700 text-white font-semibold px-5 py-2 rounded-lg transition-colors"
              >
                Sign out
              </button>
            </form>
          </div>
        </div>
      </div>
    </main>
  );
}
