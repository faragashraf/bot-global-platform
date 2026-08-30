import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import {
  provideHttpClient,
  withInterceptors
} from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeArEG from '@angular/common/locales/ar-EG';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter } from '@angular/router';
import { providePrimeNG } from 'primeng/config';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { provideTranslateService } from '@ngx-translate/core';

import { routes } from './app.routes';
import { BotGlobalPreset } from './core/theme/bot-global.preset';
import { CATALOG_REPOSITORY } from './features/catalog/services/catalog.repository';
import { HttpCatalogRepository } from './features/catalog/services/http-catalog.repository';

import { apiBaseUrlInterceptor } from './core/http/api-base-url.interceptor';

registerLocaleData(localeArEG);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(
      withInterceptors([
        apiBaseUrlInterceptor,
      ]),
    ),
    provideAnimationsAsync(),
    provideRouter(routes),
    providePrimeNG({
      theme: {
        preset: BotGlobalPreset,
        options: {
          darkModeSelector: '.bgp-dark-mode'
        }
      },
      ripple: true
    }),
    provideTranslateService({
      lang: 'en',
      fallbackLang: 'en',
      loader: provideTranslateHttpLoader({
        prefix: '/i18n/',
        suffix: '.json'
      })
    }),
    {
      provide: CATALOG_REPOSITORY,
      useExisting: HttpCatalogRepository
    }
  ]
};
