import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

export type SupportedLanguage = 'ar' | 'en';
const STORAGE_KEY = 'bgp-language';

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly document = inject(DOCUMENT);
  private readonly translate = inject(TranslateService);

  readonly language = signal<SupportedLanguage>(this.readLanguage());

  constructor() {
    this.translate.addLangs(['ar', 'en']);
    this.translate.setFallbackLang('en');
    this.apply(this.language());
  }

  setLanguage(language: SupportedLanguage): void {
    this.language.set(language);
    this.apply(language);
  }

  private apply(language: SupportedLanguage): void {
    this.translate.use(language);
    const root = this.document.documentElement;
    root.lang = language;
    root.dir = language === 'ar' ? 'rtl' : 'ltr';

    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(STORAGE_KEY, language);
    }
  }

  private readLanguage(): SupportedLanguage {
    if (typeof localStorage === 'undefined') return 'en';

    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'ar' || stored === 'en') return stored;

    return navigator.language.toLowerCase().startsWith('ar') ? 'ar' : 'en';
  }
}
