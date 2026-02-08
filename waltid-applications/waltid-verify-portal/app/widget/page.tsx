'use client';

import { useEffect, useState } from 'react';
import DashboardLayout from '@/components/DashboardLayout';
import { apiClient, WidgetConfig, WidgetSnippets } from '@/lib/api-client';

export default function WidgetPage() {
  const [config, setConfig] = useState<WidgetConfig | null>(null);
  const [snippets, setSnippets] = useState<WidgetSnippets | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [origins, setOrigins] = useState<string[]>([]);
  const [newOrigin, setNewOrigin] = useState('');
  const [originError, setOriginError] = useState('');
  const [selectedSnippet, setSelectedSnippet] = useState(0);
  const [copied, setCopied] = useState(false);
  const [saveMessage, setSaveMessage] = useState('');

  useEffect(() => {
    loadData();
  }, []);

  async function loadData() {
    try {
      const [configData, snippetsData] = await Promise.all([
        apiClient.getWidgetConfig(),
        apiClient.getWidgetSnippets(),
      ]);
      setConfig(configData);
      setSnippets(snippetsData);
      setOrigins(configData.allowedOrigins);
    } catch (error) {
      console.error('Failed to load widget config:', error);
    } finally {
      setIsLoading(false);
    }
  }

  function validateOrigin(origin: string): string | null {
    const trimmed = origin.trim();
    if (!trimmed) return 'Origin cannot be empty';
    if (trimmed === '*') return null; // Wildcard is allowed
    if (trimmed.endsWith('/')) return 'Origin must not end with a trailing slash';

    const pattern = /^https?:\/\/[a-zA-Z0-9][-a-zA-Z0-9]*(\.[a-zA-Z0-9][-a-zA-Z0-9]*)*(:\d{1,5})?$/;
    if (!pattern.test(trimmed)) {
      return 'Invalid origin format. Must be http(s)://domain[:port]';
    }

    if (origins.includes(trimmed)) {
      return 'Origin already exists';
    }

    return null;
  }

  function handleAddOrigin() {
    const error = validateOrigin(newOrigin);
    if (error) {
      setOriginError(error);
      return;
    }
    setOrigins([...origins, newOrigin.trim()]);
    setNewOrigin('');
    setOriginError('');
  }

  function handleRemoveOrigin(index: number) {
    const newOrigins = [...origins];
    newOrigins.splice(index, 1);
    setOrigins(newOrigins);
  }

  async function handleSave() {
    setIsSaving(true);
    setSaveMessage('');
    try {
      const updated = await apiClient.updateWidgetConfig(origins);
      setConfig(updated);
      setSaveMessage('Configuration saved successfully!');
      // Refresh snippets
      const snippetsData = await apiClient.getWidgetSnippets();
      setSnippets(snippetsData);
    } catch (error) {
      console.error('Failed to save widget config:', error);
      setSaveMessage('Failed to save configuration. Please try again.');
    } finally {
      setIsSaving(false);
    }
  }

  function copyToClipboard(text: string) {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  const hasChanges =
    config && JSON.stringify(origins.sort()) !== JSON.stringify(config.allowedOrigins.sort());

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Header */}
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Widget Configuration</h1>
          <p className="mt-1 text-sm text-gray-500">
            Configure your verification widget for seamless integration on your website.
          </p>
        </div>

        {isLoading ? (
          <div className="flex justify-center py-12">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
          </div>
        ) : (
          <>
            {/* Allowed Origins */}
            <div className="card">
              <div className="card-header">
                <h3 className="text-lg font-medium text-gray-900">Allowed Origins</h3>
                <p className="mt-1 text-sm text-gray-500">
                  Specify which domains can embed the verification widget. This protects against
                  unauthorized use.
                </p>
              </div>
              <div className="card-body space-y-4">
                {/* Add origin */}
                <div className="flex gap-2">
                  <div className="flex-1">
                    <input
                      type="text"
                      value={newOrigin}
                      onChange={(e) => {
                        setNewOrigin(e.target.value);
                        setOriginError('');
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault();
                          handleAddOrigin();
                        }
                      }}
                      placeholder="https://example.com"
                      className="input"
                    />
                    {originError && <p className="mt-1 text-sm text-red-600">{originError}</p>}
                  </div>
                  <button type="button" onClick={handleAddOrigin} className="btn-secondary">
                    Add
                  </button>
                </div>

                {/* Origins list */}
                {origins.length === 0 ? (
                  <div className="text-center py-4 text-gray-500">
                    <p>No origins configured. Add at least one origin to use the widget.</p>
                  </div>
                ) : (
                  <ul className="divide-y divide-gray-200 border border-gray-200 rounded-md">
                    {origins.map((origin, index) => (
                      <li key={index} className="flex items-center justify-between py-3 px-4">
                        <code className="text-sm text-gray-900">{origin}</code>
                        <button
                          type="button"
                          onClick={() => handleRemoveOrigin(index)}
                          className="text-red-600 hover:text-red-900 text-sm"
                        >
                          Remove
                        </button>
                      </li>
                    ))}
                  </ul>
                )}

                {/* Save button */}
                <div className="flex items-center justify-between pt-4 border-t border-gray-200">
                  <div>
                    {saveMessage && (
                      <p
                        className={`text-sm ${
                          saveMessage.includes('Failed') ? 'text-red-600' : 'text-green-600'
                        }`}
                      >
                        {saveMessage}
                      </p>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={handleSave}
                    disabled={isSaving || !hasChanges}
                    className="btn-primary disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {isSaving ? 'Saving...' : 'Save Changes'}
                  </button>
                </div>
              </div>
            </div>

            {/* Available Templates */}
            {config && config.availableTemplates.length > 0 && (
              <div className="card">
                <div className="card-header">
                  <h3 className="text-lg font-medium text-gray-900">Available Templates</h3>
                  <p className="mt-1 text-sm text-gray-500">
                    Templates define what information is requested during verification.
                  </p>
                </div>
                <div className="overflow-x-auto">
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                          Name
                        </th>
                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                          Type
                        </th>
                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                          Description
                        </th>
                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                          Source
                        </th>
                      </tr>
                    </thead>
                    <tbody className="bg-white divide-y divide-gray-200">
                      {config.availableTemplates.map((template) => (
                        <tr key={template.id}>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <div className="text-sm font-medium text-gray-900">
                              {template.displayName || template.name}
                            </div>
                            <div className="text-sm text-gray-500">{template.name}</div>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <span
                              className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                                template.type === 'identity'
                                  ? 'bg-blue-100 text-blue-800'
                                  : template.type === 'payment'
                                  ? 'bg-green-100 text-green-800'
                                  : 'bg-gray-100 text-gray-800'
                              }`}
                            >
                              {template.type}
                            </span>
                          </td>
                          <td className="px-6 py-4">
                            <div className="text-sm text-gray-500 max-w-md truncate">
                              {template.description || 'No description'}
                            </div>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <span
                              className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                                template.isSystem
                                  ? 'bg-purple-100 text-purple-800'
                                  : 'bg-gray-100 text-gray-800'
                              }`}
                            >
                              {template.isSystem ? 'System' : 'Custom'}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {/* Code Snippets */}
            {snippets && snippets.snippets.length > 0 && (
              <div className="card">
                <div className="card-header">
                  <h3 className="text-lg font-medium text-gray-900">Integration Code</h3>
                  <p className="mt-1 text-sm text-gray-500">
                    Copy-paste ready code snippets for integrating the widget.
                  </p>
                </div>
                <div className="card-body">
                  {/* Tab buttons */}
                  <div className="border-b border-gray-200 mb-4">
                    <nav className="-mb-px flex space-x-8">
                      {snippets.snippets.map((snippet, index) => (
                        <button
                          key={index}
                          onClick={() => setSelectedSnippet(index)}
                          className={`whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm ${
                            selectedSnippet === index
                              ? 'border-primary-500 text-primary-600'
                              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                          }`}
                        >
                          {snippet.language.toUpperCase()}
                        </button>
                      ))}
                    </nav>
                  </div>

                  {/* Code block */}
                  <div className="relative">
                    <pre className="bg-gray-900 text-gray-100 rounded-lg p-4 overflow-x-auto text-sm">
                      <code>{snippets.snippets[selectedSnippet]?.code}</code>
                    </pre>
                    <button
                      type="button"
                      onClick={() =>
                        copyToClipboard(snippets.snippets[selectedSnippet]?.code || '')
                      }
                      className="absolute top-2 right-2 px-3 py-1 bg-gray-700 hover:bg-gray-600 text-white text-sm rounded"
                    >
                      {copied ? 'Copied!' : 'Copy'}
                    </button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </DashboardLayout>
  );
}
