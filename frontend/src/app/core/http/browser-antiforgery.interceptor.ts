import {
  HttpBackend,
  HttpClient,
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, shareReplay, switchMap, tap, throwError } from 'rxjs';

import { API_BASE_URL, buildApiUrl } from '../config/api-base-url';

const TOKEN_PATH = '/api/security/antiforgery';
const TOKEN_HEADER = 'X-XSRF-TOKEN';

@Injectable({ providedIn: 'root' })
class BrowserAntiforgeryToken {
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly endpoint = buildApiUrl(inject(API_BASE_URL), TOKEN_PATH);
  private tokenRequest?: Observable<string>;

  get(): Observable<string> {
    return this.tokenRequest ??= this.http.get<{ requestToken: string }>(
      this.endpoint, { withCredentials: true },
    ).pipe(
      map(response => {
        if (!response.requestToken) {
          throw new Error('Request verification is unavailable.');
        }
        return response.requestToken;
      }),
      catchError(error => {
        this.clear();
        return throwError(() => error);
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
  }

  clear(): void {
    this.tokenRequest = undefined;
  }
}

// Angular's cookie extractor cannot read a cookie on a separately hosted API.
// Use one framework-token integration for both supported deployment layouts.
export const browserAntiforgeryInterceptor: HttpInterceptorFn = (request, next) => {
  const api = new URL(buildApiUrl(inject(API_BASE_URL), '/api/'), window.location.origin);
  const target = new URL(request.url, window.location.origin);
  if (!['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method)
    || target.origin !== api.origin
    || !target.pathname.startsWith(api.pathname)
    || request.headers.has('Authorization')
    || request.headers.has('X-Platform-Client-Key')
    || request.headers.has('X-Platform-Client-Secret')) {
    return next(request);
  }

  const token = inject(BrowserAntiforgeryToken);
  const changesSession = target.pathname === `${api.pathname}identity/login`
    || target.pathname === `${api.pathname}identity/logout`;

  return token.get().pipe(
    switchMap(value => next(request.clone({
      withCredentials: true,
      setHeaders: { [TOKEN_HEADER]: value },
    }))),
    tap({
      next: response => {
        if (changesSession && response instanceof HttpResponse) {
          token.clear();
        }
      },
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse
          && (error.status === 401
            || error.error?.code === 'antiforgery_validation_failed')) {
          token.clear();
        }
      },
    }),
  );
};
