'use client';

import { useState } from 'react';
import DashboardLayout from '@/components/DashboardLayout';
import { useAuth } from '@/lib/auth-context';

export default function SettingsPage() {
  const { user } = useAuth();
  const [isSaving, setIsSaving] = useState(false);
  const [message, setMessage] = useState({ type: '', text: '' });

  // For now, settings are read-only since the backend doesn't have update endpoints
  // This page serves as a placeholder for future functionality

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Header */}
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Settings</h1>
          <p className="mt-1 text-sm text-gray-500">
            Manage your organization settings and preferences.
          </p>
        </div>

        {/* Organization Settings */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-lg font-medium text-gray-900">Organization</h3>
          </div>
          <div className="card-body space-y-4">
            <div>
              <label htmlFor="orgName" className="label">
                Organization Name
              </label>
              <input
                type="text"
                id="orgName"
                value={user?.organization?.name || ''}
                disabled
                className="input bg-gray-50"
              />
              <p className="mt-1 text-sm text-gray-500">
                Contact support to change your organization name.
              </p>
            </div>

            <div>
              <label htmlFor="orgId" className="label">
                Organization ID
              </label>
              <input
                type="text"
                id="orgId"
                value={user?.organization?.id || ''}
                disabled
                className="input bg-gray-50 font-mono text-sm"
              />
            </div>
          </div>
        </div>

        {/* Account Settings */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-lg font-medium text-gray-900">Account</h3>
          </div>
          <div className="card-body space-y-4">
            <div>
              <label htmlFor="email" className="label">
                Email Address
              </label>
              <input
                type="email"
                id="email"
                value={user?.email || ''}
                disabled
                className="input bg-gray-50"
              />
            </div>

            <div>
              <label htmlFor="role" className="label">
                Role
              </label>
              <input
                type="text"
                id="role"
                value={user?.role || ''}
                disabled
                className="input bg-gray-50 capitalize"
              />
            </div>

            <div>
              <label htmlFor="userId" className="label">
                User ID
              </label>
              <input
                type="text"
                id="userId"
                value={user?.id || ''}
                disabled
                className="input bg-gray-50 font-mono text-sm"
              />
            </div>
          </div>
        </div>

        {/* Plan Information */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-lg font-medium text-gray-900">Plan</h3>
          </div>
          <div className="card-body">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-gray-900">Free Plan</p>
                <p className="text-sm text-gray-500">Perfect for development and testing</p>
              </div>
              <span className="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-green-100 text-green-800">
                Active
              </span>
            </div>

            <div className="mt-4 pt-4 border-t border-gray-200">
              <h4 className="text-sm font-medium text-gray-900 mb-2">Plan Features</h4>
              <ul className="space-y-2">
                <li className="flex items-center text-sm text-gray-500">
                  <svg
                    className="h-5 w-5 text-green-500 mr-2"
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
                  Unlimited test verifications
                </li>
                <li className="flex items-center text-sm text-gray-500">
                  <svg
                    className="h-5 w-5 text-green-500 mr-2"
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
                  Up to 100 live verifications/month
                </li>
                <li className="flex items-center text-sm text-gray-500">
                  <svg
                    className="h-5 w-5 text-green-500 mr-2"
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
                  Basic analytics
                </li>
                <li className="flex items-center text-sm text-gray-500">
                  <svg
                    className="h-5 w-5 text-green-500 mr-2"
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
                  Email support
                </li>
              </ul>
            </div>

            <div className="mt-4">
              <button type="button" className="btn-secondary" disabled>
                Upgrade Plan (Coming Soon)
              </button>
            </div>
          </div>
        </div>

        {/* Danger Zone */}
        <div className="card border-red-200">
          <div className="card-header bg-red-50">
            <h3 className="text-lg font-medium text-red-900">Danger Zone</h3>
          </div>
          <div className="card-body">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-gray-900">Delete Organization</p>
                <p className="text-sm text-gray-500">
                  Permanently delete your organization and all associated data.
                </p>
              </div>
              <button type="button" className="btn-danger" disabled>
                Delete Organization
              </button>
            </div>
          </div>
        </div>
      </div>
    </DashboardLayout>
  );
}
