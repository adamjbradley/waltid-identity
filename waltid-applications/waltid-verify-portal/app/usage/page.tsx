'use client';

import { useEffect, useState } from 'react';
import DashboardLayout from '@/components/DashboardLayout';
import { apiClient, UsageAnalytics, DailyUsage } from '@/lib/api-client';

function StatCard({
  title,
  value,
  subtitle,
}: {
  title: string;
  value: string | number;
  subtitle?: string;
}) {
  return (
    <div className="bg-white overflow-hidden shadow rounded-lg">
      <div className="p-5">
        <div className="flex items-center">
          <div className="flex-1">
            <dt className="text-sm font-medium text-gray-500 truncate">{title}</dt>
            <dd className="mt-1 text-3xl font-semibold text-gray-900">{value}</dd>
            {subtitle && <p className="mt-1 text-sm text-gray-500">{subtitle}</p>}
          </div>
        </div>
      </div>
    </div>
  );
}

function UsageChart({ data }: { data: DailyUsage[] }) {
  if (data.length === 0) {
    return (
      <div className="h-64 flex items-center justify-center text-gray-500">
        No usage data available
      </div>
    );
  }

  const maxCount = Math.max(...data.map((d) => d.count), 1);
  const chartHeight = 200;

  return (
    <div className="h-64">
      <div className="flex items-end justify-between h-full gap-1">
        {data.map((day, index) => {
          const height = (day.count / maxCount) * chartHeight;
          const date = new Date(day.date);
          const isToday =
            date.toDateString() === new Date().toDateString();

          return (
            <div
              key={index}
              className="flex-1 flex flex-col items-center justify-end group"
            >
              <div className="relative w-full">
                {/* Tooltip */}
                <div className="absolute bottom-full left-1/2 transform -translate-x-1/2 mb-2 opacity-0 group-hover:opacity-100 transition-opacity z-10">
                  <div className="bg-gray-900 text-white text-xs rounded py-1 px-2 whitespace-nowrap">
                    <div className="font-medium">{day.count} verifications</div>
                    <div className="text-gray-400">
                      {date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                    </div>
                  </div>
                </div>
                {/* Bar */}
                <div
                  className={`w-full rounded-t transition-all ${
                    isToday ? 'bg-primary-600' : 'bg-primary-400'
                  } hover:bg-primary-500`}
                  style={{ height: `${Math.max(height, 2)}px` }}
                />
              </div>
              {/* X-axis label (show every 7 days) */}
              {index % 7 === 0 && (
                <div className="mt-2 text-xs text-gray-500">
                  {date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default function UsagePage() {
  const [analytics, setAnalytics] = useState<UsageAnalytics | null>(null);
  const [dailyUsage, setDailyUsage] = useState<DailyUsage[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [days, setDays] = useState(30);
  const [environment, setEnvironment] = useState<'live' | 'test' | 'all'>('all');

  useEffect(() => {
    loadData();
  }, [days, environment]);

  async function loadData() {
    setIsLoading(true);
    try {
      const envFilter = environment === 'all' ? undefined : environment;

      const [analyticsData, dailyData] = await Promise.all([
        apiClient.getUsageAnalytics({ environment: envFilter }),
        apiClient.getDailyUsage(days, envFilter),
      ]);

      setAnalytics(analyticsData);
      setDailyUsage(dailyData);
    } catch (error) {
      console.error('Failed to load usage data:', error);
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Header */}
        <div className="sm:flex sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Usage Analytics</h1>
            <p className="mt-1 text-sm text-gray-500">
              Track your verification activity and monitor usage trends.
            </p>
          </div>
          <div className="mt-4 sm:mt-0 flex gap-2">
            <select
              value={environment}
              onChange={(e) => setEnvironment(e.target.value as typeof environment)}
              className="input"
            >
              <option value="all">All Environments</option>
              <option value="live">Live Only</option>
              <option value="test">Test Only</option>
            </select>
            <select
              value={days}
              onChange={(e) => setDays(parseInt(e.target.value))}
              className="input"
            >
              <option value="7">Last 7 days</option>
              <option value="30">Last 30 days</option>
              <option value="90">Last 90 days</option>
            </select>
          </div>
        </div>

        {isLoading ? (
          <div className="flex justify-center py-12">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
          </div>
        ) : (
          <>
            {/* Stats overview */}
            <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard title="Today" value={analytics?.period.today || 0} />
              <StatCard title="This Week" value={analytics?.period.thisWeek || 0} />
              <StatCard title="This Month" value={analytics?.period.thisMonth || 0} />
              <StatCard title="All Time" value={analytics?.period.allTime || 0} />
            </div>

            {/* Usage chart */}
            <div className="card">
              <div className="card-header">
                <h3 className="text-lg font-medium text-gray-900">Daily Verifications</h3>
              </div>
              <div className="card-body">
                <UsageChart data={dailyUsage} />
              </div>
            </div>

            {/* Breakdown */}
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
              {/* By Status */}
              <div className="card">
                <div className="card-header">
                  <h3 className="text-lg font-medium text-gray-900">By Status</h3>
                </div>
                <div className="card-body">
                  {analytics && Object.keys(analytics.byStatus).length > 0 ? (
                    <div className="space-y-4">
                      {Object.entries(analytics.byStatus).map(([status, count]) => {
                        const total = Object.values(analytics.byStatus).reduce((a, b) => a + b, 0);
                        const percentage = total > 0 ? (count / total) * 100 : 0;

                        return (
                          <div key={status}>
                            <div className="flex items-center justify-between mb-1">
                              <span className="text-sm font-medium text-gray-900 capitalize">
                                {status}
                              </span>
                              <span className="text-sm text-gray-500">
                                {count} ({percentage.toFixed(1)}%)
                              </span>
                            </div>
                            <div className="w-full bg-gray-200 rounded-full h-2">
                              <div
                                className={`h-2 rounded-full ${
                                  status === 'verified'
                                    ? 'bg-green-500'
                                    : status === 'failed'
                                    ? 'bg-red-500'
                                    : status === 'pending'
                                    ? 'bg-yellow-500'
                                    : 'bg-gray-500'
                                }`}
                                style={{ width: `${percentage}%` }}
                              />
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="text-center py-8 text-gray-500">No status data available</div>
                  )}
                </div>
              </div>

              {/* By Environment */}
              <div className="card">
                <div className="card-header">
                  <h3 className="text-lg font-medium text-gray-900">By Environment</h3>
                </div>
                <div className="card-body">
                  {analytics && Object.keys(analytics.byEnvironment).length > 0 ? (
                    <div className="space-y-4">
                      {Object.entries(analytics.byEnvironment).map(([env, count]) => {
                        const total = Object.values(analytics.byEnvironment).reduce(
                          (a, b) => a + b,
                          0
                        );
                        const percentage = total > 0 ? (count / total) * 100 : 0;

                        return (
                          <div key={env}>
                            <div className="flex items-center justify-between mb-1">
                              <span className="text-sm font-medium text-gray-900 capitalize">
                                {env}
                              </span>
                              <span className="text-sm text-gray-500">
                                {count} ({percentage.toFixed(1)}%)
                              </span>
                            </div>
                            <div className="w-full bg-gray-200 rounded-full h-2">
                              <div
                                className={`h-2 rounded-full ${
                                  env === 'live' ? 'bg-green-500' : 'bg-yellow-500'
                                }`}
                                style={{ width: `${percentage}%` }}
                              />
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="text-center py-8 text-gray-500">
                      No environment data available
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* By Template */}
            {analytics && Object.keys(analytics.byTemplate).length > 0 && (
              <div className="card">
                <div className="card-header">
                  <h3 className="text-lg font-medium text-gray-900">By Template</h3>
                </div>
                <div className="overflow-x-auto">
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                          Template
                        </th>
                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                          Verifications
                        </th>
                        <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                          Percentage
                        </th>
                      </tr>
                    </thead>
                    <tbody className="bg-white divide-y divide-gray-200">
                      {Object.entries(analytics.byTemplate)
                        .sort(([, a], [, b]) => b - a)
                        .map(([template, count]) => {
                          const total = Object.values(analytics.byTemplate).reduce(
                            (a, b) => a + b,
                            0
                          );
                          const percentage = total > 0 ? (count / total) * 100 : 0;

                          return (
                            <tr key={template}>
                              <td className="px-6 py-4 whitespace-nowrap">
                                <div className="text-sm font-medium text-gray-900">{template}</div>
                              </td>
                              <td className="px-6 py-4 whitespace-nowrap">
                                <div className="text-sm text-gray-900">{count}</div>
                              </td>
                              <td className="px-6 py-4 whitespace-nowrap">
                                <div className="flex items-center">
                                  <div className="w-24 bg-gray-200 rounded-full h-2 mr-2">
                                    <div
                                      className="bg-primary-500 h-2 rounded-full"
                                      style={{ width: `${percentage}%` }}
                                    />
                                  </div>
                                  <span className="text-sm text-gray-500">
                                    {percentage.toFixed(1)}%
                                  </span>
                                </div>
                              </td>
                            </tr>
                          );
                        })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </DashboardLayout>
  );
}
