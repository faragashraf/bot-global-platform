import {
  HttpClient,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';

import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import {
  TestBed,
} from '@angular/core/testing';

import {
  API_BASE_URL,
} from '../config/api-base-url';

import {
  apiBaseUrlInterceptor,
} from './api-base-url.interceptor';

describe('apiBaseUrlInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([
            apiBaseUrlInterceptor,
          ]),
        ),
        provideHttpClientTesting(),
        {
          provide: API_BASE_URL,
          useValue:
            'https://dayoub.challengershoes.com',
        },
      ],
    });

    http = TestBed.inject(HttpClient);

    httpTesting =
      TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('rewrites relative api requests to configured base url', () => {
    http
      .get('/api/catalog/products')
      .subscribe();

    const request =
      httpTesting.expectOne(
        'https://dayoub.challengershoes.com/api/catalog/products',
      );

    expect(
      request.request.withCredentials,
    ).toBe(true);

    request.flush([]);
  });

  it('does not rewrite non-api requests', () => {
    http
      .get('/runtime-config.js')
      .subscribe();

    const request =
      httpTesting.expectOne(
        '/runtime-config.js',
      );

    expect(
      request.request.withCredentials,
    ).toBe(false);

    request.flush({});
  });
});
