'use client';

import { useEffect, useState, Fragment } from 'react';
import { Dialog, Transition } from '@headlessui/react';
import DashboardLayout from '@/components/DashboardLayout';
import { apiClient, ApiKey, CreateApiKeyResponse } from '@/lib/api-client';

function formatDate(dateString: string | null): string {
  if (!dateString) return 'Never';
  return new Date(dateString).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default function ApiKeysPage() {
  const [apiKeys, setApiKeys] = useState<ApiKey[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isNewKeyModalOpen, setIsNewKeyModalOpen] = useState(false);
  const [newKey, setNewKey] = useState<CreateApiKeyResponse | null>(null);
  const [keyName, setKeyName] = useState('');
  const [keyEnvironment, setKeyEnvironment] = useState<'live' | 'test'>('test');
  const [isCreating, setIsCreating] = useState(false);
  const [isRevoking, setIsRevoking] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    loadApiKeys();
  }, []);

  async function loadApiKeys() {
    try {
      const keys = await apiClient.getApiKeys();
      setApiKeys(keys);
    } catch (error) {
      console.error('Failed to load API keys:', error);
    } finally {
      setIsLoading(false);
    }
  }

  async function handleCreateKey() {
    setIsCreating(true);
    try {
      const key = await apiClient.createApiKey(keyName || undefined, keyEnvironment);
      setNewKey(key);
      setIsCreateModalOpen(false);
      setIsNewKeyModalOpen(true);
      setKeyName('');
      setKeyEnvironment('test');
      await loadApiKeys();
    } catch (error) {
      console.error('Failed to create API key:', error);
      alert('Failed to create API key. Please try again.');
    } finally {
      setIsCreating(false);
    }
  }

  async function handleRevokeKey(keyId: string) {
    if (!confirm('Are you sure you want to revoke this API key? This action cannot be undone.')) {
      return;
    }

    setIsRevoking(keyId);
    try {
      await apiClient.revokeApiKey(keyId);
      await loadApiKeys();
    } catch (error) {
      console.error('Failed to revoke API key:', error);
      alert('Failed to revoke API key. Please try again.');
    } finally {
      setIsRevoking(null);
    }
  }

  function copyToClipboard(text: string) {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  const activeKeys = apiKeys.filter((key) => !key.revoked);
  const revokedKeys = apiKeys.filter((key) => key.revoked);

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Header */}
        <div className="sm:flex sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">API Keys</h1>
            <p className="mt-1 text-sm text-gray-500">
              Manage your API keys for authentication. Keep your keys secure and never share them
              publicly.
            </p>
          </div>
          <div className="mt-4 sm:mt-0">
            <button
              type="button"
              onClick={() => setIsCreateModalOpen(true)}
              className="btn-primary"
            >
              <svg
                className="-ml-1 mr-2 h-5 w-5"
                fill="none"
                viewBox="0 0 24 24"
                strokeWidth={1.5}
                stroke="currentColor"
              >
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
              </svg>
              Create API Key
            </button>
          </div>
        </div>

        {/* Active keys */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-lg font-medium text-gray-900">Active Keys</h3>
          </div>
          <div className="overflow-x-auto">
            {isLoading ? (
              <div className="p-6 text-center">
                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600 mx-auto"></div>
              </div>
            ) : activeKeys.length === 0 ? (
              <div className="p-6 text-center text-gray-500">
                <p>No active API keys. Create one to get started.</p>
              </div>
            ) : (
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Name
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Key
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Environment
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Last Used
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Created
                    </th>
                    <th className="relative px-6 py-3">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {activeKeys.map((key) => (
                    <tr key={key.id}>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div className="text-sm font-medium text-gray-900">
                          {key.name || 'Unnamed'}
                        </div>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <code className="text-sm text-gray-600 bg-gray-100 px-2 py-1 rounded">
                          {key.keyPrefix}...
                        </code>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span
                          className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                            key.environment === 'live'
                              ? 'bg-green-100 text-green-800'
                              : 'bg-yellow-100 text-yellow-800'
                          }`}
                        >
                          {key.environment}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        {formatDate(key.lastUsedAt)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        {formatDate(key.createdAt)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                        <button
                          onClick={() => handleRevokeKey(key.id)}
                          disabled={isRevoking === key.id}
                          className="text-red-600 hover:text-red-900 disabled:opacity-50"
                        >
                          {isRevoking === key.id ? 'Revoking...' : 'Revoke'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>

        {/* Revoked keys */}
        {revokedKeys.length > 0 && (
          <div className="card">
            <div className="card-header">
              <h3 className="text-lg font-medium text-gray-500">Revoked Keys</h3>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Name
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Key
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Environment
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Created
                    </th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {revokedKeys.map((key) => (
                    <tr key={key.id} className="opacity-50">
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div className="text-sm font-medium text-gray-900">
                          {key.name || 'Unnamed'}
                        </div>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <code className="text-sm text-gray-600 bg-gray-100 px-2 py-1 rounded">
                          {key.keyPrefix}... (revoked)
                        </code>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800">
                          {key.environment}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        {formatDate(key.createdAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      {/* Create API Key Modal */}
      <Transition.Root show={isCreateModalOpen} as={Fragment}>
        <Dialog as="div" className="relative z-50" onClose={setIsCreateModalOpen}>
          <Transition.Child
            as={Fragment}
            enter="ease-out duration-300"
            enterFrom="opacity-0"
            enterTo="opacity-100"
            leave="ease-in duration-200"
            leaveFrom="opacity-100"
            leaveTo="opacity-0"
          >
            <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" />
          </Transition.Child>

          <div className="fixed inset-0 z-10 overflow-y-auto">
            <div className="flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0">
              <Transition.Child
                as={Fragment}
                enter="ease-out duration-300"
                enterFrom="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
                enterTo="opacity-100 translate-y-0 sm:scale-100"
                leave="ease-in duration-200"
                leaveFrom="opacity-100 translate-y-0 sm:scale-100"
                leaveTo="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
              >
                <Dialog.Panel className="relative transform overflow-hidden rounded-lg bg-white px-4 pb-4 pt-5 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6">
                  <div>
                    <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-primary-100">
                      <svg
                        className="h-6 w-6 text-primary-600"
                        fill="none"
                        viewBox="0 0 24 24"
                        strokeWidth={1.5}
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          d="M15.75 5.25a3 3 0 013 3m3 0a6 6 0 01-7.029 5.912c-.563-.097-1.159.026-1.563.43L10.5 17.25H8.25v2.25H6v2.25H2.25v-2.818c0-.597.237-1.17.659-1.591l6.499-6.499c.404-.404.527-1 .43-1.563A6 6 0 1121.75 8.25z"
                        />
                      </svg>
                    </div>
                    <div className="mt-3 text-center sm:mt-5">
                      <Dialog.Title
                        as="h3"
                        className="text-lg font-semibold leading-6 text-gray-900"
                      >
                        Create API Key
                      </Dialog.Title>
                      <div className="mt-4 space-y-4">
                        <div className="text-left">
                          <label htmlFor="keyName" className="label">
                            Name (optional)
                          </label>
                          <input
                            type="text"
                            id="keyName"
                            value={keyName}
                            onChange={(e) => setKeyName(e.target.value)}
                            placeholder="My API Key"
                            className="input"
                          />
                        </div>
                        <div className="text-left">
                          <label htmlFor="environment" className="label">
                            Environment
                          </label>
                          <select
                            id="environment"
                            value={keyEnvironment}
                            onChange={(e) =>
                              setKeyEnvironment(e.target.value as 'live' | 'test')
                            }
                            className="input"
                          >
                            <option value="test">Test</option>
                            <option value="live">Live</option>
                          </select>
                          <p className="mt-1 text-sm text-gray-500">
                            {keyEnvironment === 'test'
                              ? 'Test keys are for development and testing.'
                              : 'Live keys are for production use.'}
                          </p>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div className="mt-5 sm:mt-6 sm:grid sm:grid-flow-row-dense sm:grid-cols-2 sm:gap-3">
                    <button
                      type="button"
                      onClick={handleCreateKey}
                      disabled={isCreating}
                      className="btn-primary inline-flex w-full justify-center sm:col-start-2"
                    >
                      {isCreating ? 'Creating...' : 'Create Key'}
                    </button>
                    <button
                      type="button"
                      onClick={() => setIsCreateModalOpen(false)}
                      className="btn-secondary mt-3 inline-flex w-full justify-center sm:col-start-1 sm:mt-0"
                    >
                      Cancel
                    </button>
                  </div>
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </Dialog>
      </Transition.Root>

      {/* New Key Created Modal */}
      <Transition.Root show={isNewKeyModalOpen} as={Fragment}>
        <Dialog as="div" className="relative z-50" onClose={() => {}}>
          <Transition.Child
            as={Fragment}
            enter="ease-out duration-300"
            enterFrom="opacity-0"
            enterTo="opacity-100"
            leave="ease-in duration-200"
            leaveFrom="opacity-100"
            leaveTo="opacity-0"
          >
            <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" />
          </Transition.Child>

          <div className="fixed inset-0 z-10 overflow-y-auto">
            <div className="flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0">
              <Transition.Child
                as={Fragment}
                enter="ease-out duration-300"
                enterFrom="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
                enterTo="opacity-100 translate-y-0 sm:scale-100"
                leave="ease-in duration-200"
                leaveFrom="opacity-100 translate-y-0 sm:scale-100"
                leaveTo="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
              >
                <Dialog.Panel className="relative transform overflow-hidden rounded-lg bg-white px-4 pb-4 pt-5 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6">
                  <div>
                    <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-green-100">
                      <svg
                        className="h-6 w-6 text-green-600"
                        fill="none"
                        viewBox="0 0 24 24"
                        strokeWidth={1.5}
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          d="M4.5 12.75l6 6 9-13.5"
                        />
                      </svg>
                    </div>
                    <div className="mt-3 text-center sm:mt-5">
                      <Dialog.Title
                        as="h3"
                        className="text-lg font-semibold leading-6 text-gray-900"
                      >
                        API Key Created
                      </Dialog.Title>
                      <div className="mt-4">
                        <div className="rounded-md bg-yellow-50 p-4 mb-4">
                          <div className="flex">
                            <div className="flex-shrink-0">
                              <svg
                                className="h-5 w-5 text-yellow-400"
                                viewBox="0 0 20 20"
                                fill="currentColor"
                              >
                                <path
                                  fillRule="evenodd"
                                  d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z"
                                  clipRule="evenodd"
                                />
                              </svg>
                            </div>
                            <div className="ml-3">
                              <p className="text-sm text-yellow-700">
                                Make sure to copy your API key now. You won&apos;t be able to see it
                                again!
                              </p>
                            </div>
                          </div>
                        </div>
                        <div className="relative">
                          <input
                            type="text"
                            readOnly
                            value={newKey?.key || ''}
                            className="input pr-24 font-mono text-sm"
                          />
                          <button
                            type="button"
                            onClick={() => copyToClipboard(newKey?.key || '')}
                            className="absolute inset-y-0 right-0 flex items-center px-3 text-sm font-medium text-primary-600 hover:text-primary-500"
                          >
                            {copied ? 'Copied!' : 'Copy'}
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div className="mt-5 sm:mt-6">
                    <button
                      type="button"
                      onClick={() => {
                        setIsNewKeyModalOpen(false);
                        setNewKey(null);
                      }}
                      className="btn-primary inline-flex w-full justify-center"
                    >
                      Done
                    </button>
                  </div>
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </Dialog>
      </Transition.Root>
    </DashboardLayout>
  );
}
