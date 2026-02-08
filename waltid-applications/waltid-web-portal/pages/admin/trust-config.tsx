import { useState, useEffect, useContext } from 'react'
import axios from 'axios'
import { useRouter } from 'next/router'
import { EnvContext } from '@/pages/_app'
import Button from '@/components/walt/button/Button'
import WaltIcon from '@/components/walt/logo/WaltIcon'
import InputField from '@/components/walt/forms/Input'
import { ArrowPathIcon } from '@heroicons/react/24/outline'

// Interfaces
interface TrustSourceStatus {
  enabled: boolean
  healthy: boolean
  lastUpdate?: string
  entryCount: number
  error?: string
}

interface TrustServiceStatus {
  healthy: boolean
  sources: Record<string, TrustSourceStatus>
  lastUpdate?: string
}

interface TestValidationResult {
  trusted: boolean
  provider?: string
  country?: string
  source?: string
  message?: string
}

const SOURCE_LABELS: Record<string, { name: string; description: string }> = {
  'etsi_tl': { name: 'EU Trusted List (ETSI)', description: 'ETSI TS 119 612 Trust Service Lists from EU Member States' },
  'openid_federation': { name: 'OpenID Federation', description: 'OpenID Federation 1.0 trust chain resolution' },
  'vical': { name: 'VICAL', description: 'Verifiable Issuer Certificate Authority List' },
  'static_list': { name: 'Static Trust List', description: 'Manually configured trusted issuers' }
}

export default function TrustConfig() {
  const env = useContext(EnvContext)
  const router = useRouter()
  const [status, setStatus] = useState<TrustServiceStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  // Test validation state
  const [testDid, setTestDid] = useState('')
  const [testType, setTestType] = useState('')
  const [testResult, setTestResult] = useState<TestValidationResult | null>(null)
  const [testing, setTesting] = useState(false)
  const [testError, setTestError] = useState<string | null>(null)

  const fetchStatus = async () => {
    try {
      const response = await axios.get(`${env.NEXT_PUBLIC_VERIFIER2}/admin/trust/status`)
      setStatus(response.data)
      setError(null)
    } catch (e: any) {
      if (e.response?.status === 503) {
        setError('Trust lists feature is not enabled. Set TRUST_LISTS_ENABLED=true in the verifier configuration to enable.')
      } else {
        setError(e.response?.data?.message || e.message || 'Failed to fetch trust service status')
      }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (env.NEXT_PUBLIC_VERIFIER2) {
      fetchStatus()
    } else {
      setError('Verifier API2 is not configured (NEXT_PUBLIC_VERIFIER2)')
      setLoading(false)
    }
  }, [env.NEXT_PUBLIC_VERIFIER2])

  const handleRefresh = async () => {
    setRefreshing(true)
    try {
      await axios.post(`${env.NEXT_PUBLIC_VERIFIER2}/admin/trust/refresh`)
      await fetchStatus()
    } catch (e: any) {
      setError(e.response?.data?.message || e.message || 'Failed to refresh trust sources')
    } finally {
      setRefreshing(false)
    }
  }

  const handleToggleSource = async (sourceName: string, currentEnabled: boolean) => {
    try {
      const endpoint = sourceName === 'etsi_tl' ? 'etsi' :
                      sourceName === 'openid_federation' ? 'federation' :
                      null

      if (!endpoint) {
        console.warn('Toggle not supported for source:', sourceName)
        return
      }

      await axios.put(
        `${env.NEXT_PUBLIC_VERIFIER2}/admin/trust/${endpoint}`,
        { enabled: !currentEnabled }
      )

      await fetchStatus()
    } catch (e: any) {
      setError(e.response?.data?.message || e.message || 'Failed to toggle trust source')
    }
  }

  const handleTestValidation = async () => {
    if (!testDid.trim()) {
      setTestError('Please enter an issuer DID')
      return
    }

    setTesting(true)
    setTestError(null)
    setTestResult(null)

    try {
      const params: any = { issuerDid: testDid }
      if (testType.trim()) {
        params.credentialType = testType
      }

      const response = await axios.get(`${env.NEXT_PUBLIC_VERIFIER2}/admin/trust/test`, {
        params
      })

      setTestResult(response.data)
    } catch (e: any) {
      setTestError(e.response?.data?.message || e.message || 'Failed to test validation')
    } finally {
      setTesting(false)
    }
  }

  const formatTimestamp = (timestamp?: string) => {
    if (!timestamp) return 'Never'

    try {
      const date = new Date(timestamp)
      const now = new Date()
      const diffMs = now.getTime() - date.getTime()
      const diffMins = Math.floor(diffMs / 60000)
      const diffHours = Math.floor(diffMins / 60)
      const diffDays = Math.floor(diffHours / 24)

      if (diffMins < 1) return 'Just now'
      if (diffMins < 60) return `${diffMins}m ago`
      if (diffHours < 24) return `${diffHours}h ago`
      if (diffDays < 7) return `${diffDays}d ago`

      return date.toLocaleString()
    } catch {
      return timestamp
    }
  }

  return (
    <div className="flex flex-col justify-center items-center bg-gray-50 min-h-screen">
      <div
        className="my-5 flex flex-row justify-center cursor-pointer"
        onClick={() => router.push('/')}
      >
        <WaltIcon height={35} width={35} type="primary" />
      </div>

      <div className="w-11/12 md:w-7/12 shadow-2xl rounded-lg mt-5 pt-8 pb-8 px-10 bg-white max-w-[960px]">
        {/* Header */}
        <div className="flex flex-row justify-between items-center mb-6">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Trust List Configuration</h1>
            {status && (
              <div className="flex items-center gap-2 mt-2">
                <div className={`w-3 h-3 rounded-full ${status.healthy ? 'bg-green-500' : 'bg-red-500'}`} />
                <span className="text-sm text-gray-600">
                  {status.healthy ? 'System Healthy' : 'System Unhealthy'}
                </span>
              </div>
            )}
          </div>
          <Button
            onClick={handleRefresh}
            loading={refreshing}
            disabled={loading || !!error}
            size="sm"
            color="secondary"
          >
            <div className="flex items-center gap-2">
              <ArrowPathIcon className="w-4 h-4" />
              Refresh
            </div>
          </Button>
        </div>

        {/* Error Display */}
        {error && (
          <div className="bg-red-50 border border-red-200 rounded-lg p-4 mb-6">
            <p className="text-red-800 font-semibold">Error</p>
            <p className="text-red-600 text-sm mt-1">{error}</p>
          </div>
        )}

        {/* Loading State */}
        {loading && !error && (
          <div className="flex justify-center py-10">
            <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-400"></div>
          </div>
        )}

        {/* Trust Sources */}
        {!loading && status && (
          <>
            <div className="space-y-4 mb-8">
              <h2 className="text-lg font-semibold text-gray-800 mb-3">Trust Sources</h2>
              {Object.entries(status.sources).map(([sourceKey, source]) => {
                const label = SOURCE_LABELS[sourceKey] || { name: sourceKey, description: '' }
                const canToggle = sourceKey === 'etsi_tl' || sourceKey === 'openid_federation'

                return (
                  <div
                    key={sourceKey}
                    className="border border-gray-200 rounded-lg p-4 bg-gray-50"
                  >
                    <div className="flex justify-between items-start">
                      <div className="flex-1">
                        <div className="flex items-center gap-3 mb-2">
                          <h3 className="font-semibold text-gray-900">{label.name}</h3>
                          {canToggle && (
                            <button
                              onClick={() => handleToggleSource(sourceKey, source.enabled)}
                              className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                                source.enabled
                                  ? 'bg-green-100 text-green-800 hover:bg-green-200'
                                  : 'bg-gray-200 text-gray-600 hover:bg-gray-300'
                              }`}
                            >
                              {source.enabled ? 'Enabled' : 'Disabled'}
                            </button>
                          )}
                        </div>
                        {label.description && (
                          <p className="text-sm text-gray-600 mb-3">{label.description}</p>
                        )}
                        <div className="flex items-center gap-4 text-sm">
                          <div className="flex items-center gap-2">
                            <div className={`w-2 h-2 rounded-full ${source.healthy ? 'bg-green-500' : 'bg-red-500'}`} />
                            <span className={source.healthy ? 'text-green-700' : 'text-red-700'}>
                              {source.healthy ? 'Healthy' : 'Unhealthy'}
                            </span>
                          </div>
                          <span className="text-gray-600">
                            {source.entryCount} {source.entryCount === 1 ? 'entry' : 'entries'}
                          </span>
                          <span className="text-gray-500">
                            Updated {formatTimestamp(source.lastUpdate)}
                          </span>
                        </div>
                        {source.error && (
                          <p className="text-sm text-red-600 mt-2">Error: {source.error}</p>
                        )}
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>

            {/* Test Validation Section */}
            <div className="border-t border-gray-200 pt-6">
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Test Validation</h2>

              <div className="space-y-4">
                <InputField
                  value={testDid}
                  onChange={setTestDid}
                  type="text"
                  name="issuerDid"
                  label="Issuer DID"
                  placeholder="did:key:z6Mk..."
                  showLabel={true}
                />

                <InputField
                  value={testType}
                  onChange={setTestType}
                  type="text"
                  name="credentialType"
                  label="Credential Type (optional)"
                  placeholder="VerifiableCredential"
                  showLabel={true}
                />

                <Button
                  onClick={handleTestValidation}
                  loading={testing}
                  disabled={!testDid.trim()}
                  color="primary"
                >
                  Test Validation
                </Button>

                {testError && (
                  <div className="bg-red-50 border border-red-200 rounded-lg p-3">
                    <p className="text-red-800 text-sm">{testError}</p>
                  </div>
                )}

                {testResult && (
                  <div className={`border rounded-lg p-4 ${
                    testResult.trusted
                      ? 'bg-green-50 border-green-200'
                      : 'bg-yellow-50 border-yellow-200'
                  }`}>
                    <div className="flex items-start gap-3">
                      <div className={`text-2xl ${testResult.trusted ? 'text-green-600' : 'text-yellow-600'}`}>
                        {testResult.trusted ? '✓' : '⚠'}
                      </div>
                      <div className="flex-1">
                        <p className={`font-semibold ${testResult.trusted ? 'text-green-800' : 'text-yellow-800'}`}>
                          {testResult.trusted ? 'Trusted' : 'Not Trusted'}
                        </p>
                        {testResult.message && (
                          <p className="text-sm text-gray-600 mt-1">{testResult.message}</p>
                        )}
                        {testResult.trusted && (
                          <div className="mt-2 text-sm text-gray-700 space-y-1">
                            {testResult.source && (
                              <p>Source: <span className="font-medium">{testResult.source}</span></p>
                            )}
                            {testResult.provider && (
                              <p>Provider: <span className="font-medium">{testResult.provider}</span></p>
                            )}
                            {testResult.country && (
                              <p>Country: <span className="font-medium">{testResult.country}</span></p>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </div>
          </>
        )}

        {/* Footer */}
        <div className="flex flex-col items-center mt-8 pt-6 border-t border-gray-200">
          <div className="flex flex-row gap-2 items-center content-center text-sm text-center text-gray-500">
            <p>Secured by walt.id</p>
            <WaltIcon height={15} width={15} type="gray" />
          </div>
        </div>
      </div>
    </div>
  )
}
