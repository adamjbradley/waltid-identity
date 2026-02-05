/**
 * Device detection utilities for same-device verification flow with EUDI wallet.
 * Used to determine whether to show deep link button vs QR code only.
 */

/**
 * Detect if user is on a mobile device (Android or iOS)
 */
export function isMobileDevice(): boolean {
  if (typeof window === 'undefined') return false;

  const userAgent = navigator.userAgent || navigator.vendor || (window as any).opera;

  // Android
  if (/android/i.test(userAgent)) return true;

  // iOS
  if (/iPad|iPhone|iPod/.test(userAgent) && !(window as any).MSStream) return true;

  return false;
}

/**
 * Detect specific mobile OS for wallet-specific handling
 */
export function getMobileOS(): 'android' | 'ios' | 'other' {
  if (typeof window === 'undefined') return 'other';

  const userAgent = navigator.userAgent || navigator.vendor || (window as any).opera;

  if (/android/i.test(userAgent)) return 'android';
  if (/iPad|iPhone|iPod/.test(userAgent) && !(window as any).MSStream) return 'ios';

  return 'other';
}
