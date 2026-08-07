import { DOCUMENT } from '@angular/common';
import { Injectable, computed, effect, inject, signal } from '@angular/core';

export type ThemePreference = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

const STORAGE_KEY = 'bgp-theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly mediaQuery =
    typeof window !== 'undefined'
      ? window.matchMedia('(prefers-color-scheme: dark)')
      : null;

  readonly preference = signal<ThemePreference>(this.readPreference());
  readonly systemTheme = signal<ResolvedTheme>(
    this.mediaQuery?.matches ? 'dark' : 'light'
  );

  readonly resolvedTheme = computed<ResolvedTheme>(() => {
    const preference = this.preference();
    return preference === 'system' ? this.systemTheme() : preference;
  });

  private readonly mediaListener = (event: MediaQueryListEvent) => {
    this.systemTheme.set(event.matches ? 'dark' : 'light');
  };

  constructor() {
    this.mediaQuery?.addEventListener('change', this.mediaListener);

    effect(() => {
      const preference = this.preference();
      const resolved = this.resolvedTheme();
      const root = this.document.documentElement;

      root.classList.toggle('bgp-dark-mode', resolved === 'dark');
      root.dataset['theme'] = resolved;
      root.dataset['themePreference'] = preference;
      root.style.colorScheme = resolved;

      if (typeof localStorage !== 'undefined') {
        localStorage.setItem(STORAGE_KEY, preference);
      }
    });
  }

  setPreference(preference: ThemePreference): void {
    this.preference.set(preference);
  }

  private readPreference(): ThemePreference {
    if (typeof localStorage === 'undefined') return 'system';

    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'light' || stored === 'dark' || stored === 'system'
      ? stored
      : 'system';
  }
}
