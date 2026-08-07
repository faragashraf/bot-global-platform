import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter } from '@angular/router';
import { providePrimeNG } from 'primeng/config';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { provideTranslateService } from '@ngx-translate/core';

import { routes } from './app.routes';
import { BotGlobalPreset } from './core/theme/bot-global.preset';
import { CATALOG_REPOSITORY } from './features/catalog/services/catalog.repository';
import { HttpCatalogRepository } from './features/catalog/services/http-catalog.repository';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(),
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
