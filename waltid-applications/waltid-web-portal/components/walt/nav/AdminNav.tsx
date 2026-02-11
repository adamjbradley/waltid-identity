import React from 'react';
import { useRouter } from 'next/router';
import {
  ShieldCheckIcon,
  BuildingOffice2Icon,
  BuildingLibraryIcon,
  HomeIcon,
} from '@heroicons/react/24/outline';

const navItems = [
  {
    label: 'Trust Lists',
    path: '/admin/trust-config',
    icon: ShieldCheckIcon,
  },
  {
    label: 'Issuers',
    path: '/admin/issuers',
    icon: BuildingLibraryIcon,
  },
  {
    label: 'Relying Parties',
    path: '/admin/relying-parties',
    icon: BuildingOffice2Icon,
  },
];

export default function AdminNav() {
  const router = useRouter();

  return (
    <nav className="flex items-center gap-1 text-sm">
      <button
        onClick={() => router.push('/')}
        className="flex items-center gap-1 px-3 py-1.5 rounded-md text-gray-500 hover:text-gray-700 hover:bg-gray-100 transition-colors"
      >
        <HomeIcon className="w-4 h-4" />
        Portal
      </button>
      <span className="text-gray-300 mx-1">/</span>
      {navItems.map((item) => {
        const active = router.pathname === item.path;
        return (
          <button
            key={item.path}
            onClick={() => router.push(item.path)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md transition-colors ${
              active
                ? 'bg-blue-50 text-blue-700 font-medium'
                : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'
            }`}
          >
            <item.icon className="w-4 h-4" />
            {item.label}
          </button>
        );
      })}
    </nav>
  );
}
