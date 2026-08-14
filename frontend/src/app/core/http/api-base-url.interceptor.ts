import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import {
  API_BASE_URL,
  buildApiUrl,
} from '../config/api-base-url';

export const apiBaseUrlInterceptor: HttpInterceptorFn =
  (request, next) => {
    if (!request.url.startsWith('/api/')) {
      return next(request);
    }

    const apiBaseUrl = inject(API_BASE_URL);

    return next(
      request.clone({
        url: buildApiUrl(
          apiBaseUrl,
          request.url,
        ),
        withCredentials: true,
      }),
    );
  };
