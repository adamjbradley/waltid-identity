'use client';

import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { apiClient, UserInfo, ApiClientError } from './api-client';

interface AuthContextType {
  user: UserInfo | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  signup: (email: string, password: string, organizationName: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | null>(null);

const PUBLIC_PATHS = ['/login', '/signup'];

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const router = useRouter();
  const pathname = usePathname();

  // Check if user is authenticated on mount
  useEffect(() => {
    const token = apiClient.getAccessToken();
    if (token) {
      // Try to decode user info from token (JWT)
      try {
        const payload = JSON.parse(atob(token.split('.')[1]));
        setUser({
          id: payload.sub,
          email: payload.email,
          role: payload.role,
          organization: {
            id: payload.org_id,
            name: payload.org_name,
          },
        });
      } catch {
        // Invalid token, clear it
        apiClient.clearTokens();
      }
    }
    setIsLoading(false);
  }, []);

  // Redirect logic
  useEffect(() => {
    if (!isLoading) {
      const isPublicPath = PUBLIC_PATHS.includes(pathname);

      if (!user && !isPublicPath) {
        router.replace('/login');
      } else if (user && isPublicPath) {
        router.replace('/dashboard');
      }
    }
  }, [user, isLoading, pathname, router]);

  const login = useCallback(async (email: string, password: string) => {
    const response = await apiClient.login(email, password);
    setUser(response.user);
    router.push('/dashboard');
  }, [router]);

  const signup = useCallback(async (email: string, password: string, organizationName: string) => {
    const response = await apiClient.signup(email, password, organizationName);
    setUser(response.user);
    router.push('/dashboard');
  }, [router]);

  const logout = useCallback(() => {
    apiClient.logout();
    setUser(null);
    router.push('/login');
  }, [router]);

  return (
    <AuthContext.Provider value={{ user, isLoading, login, signup, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
