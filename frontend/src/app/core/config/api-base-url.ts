import { InjectionToken } from '@angular/core';

declare global {
  interface Window {
    __BOT_GLOBAL_CONFIG__?: {
      apiBaseUrl?: string;
    };
  }
}

export const API_BASE_URL = new InjectionToken<string>(
  'API_BASE_URL',
  {
    providedIn: 'root',
    factory: () =>
      (window.__BOT_GLOBAL_CONFIG__?.apiBaseUrl ?? '')
        .replace(/\/+$/, ''),
  },
);

export function buildApiUrl(baseUrl: string, path: string): string {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${baseUrl}${normalizedPath}`;
}
