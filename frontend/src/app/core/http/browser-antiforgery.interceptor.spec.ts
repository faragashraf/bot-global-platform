import { HttpClient, HttpHeaders, provideHttpClient, withInterceptors, withNoXsrfProtection } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { API_BASE_URL } from '../config/api-base-url';
import { apiBaseUrlInterceptor } from './api-base-url.interceptor';
import { browserAntiforgeryInterceptor } from './browser-antiforgery.interceptor';

describe.each(['', 'https://api.example.test'])('browser antiforgery with API base %s', base => {
  let http: HttpClient;
  let requests: HttpTestingController;
  const header = 'X-XSRF-TOKEN';
  const tokenUrl = `${base}/api/security/antiforgery`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withNoXsrfProtection(), withInterceptors([
          apiBaseUrlInterceptor, browserAntiforgeryInterceptor,
        ])),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: base },
      ],
    });
    http = TestBed.inject(HttpClient);
    requests = TestBed.inject(HttpTestingController);
  });

  afterEach(() => requests.verify());

  function bootstrap(value = 'framework-test-proof'): void {
    const request = requests.expectOne(tokenUrl);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.has(header)).toBe(false);
    request.flush({ requestToken: value });
  }

  it.each(['POST', 'PUT', 'PATCH', 'DELETE'])('adds proof centrally for %s', method => {
    http.request(method, '/api/admin/example', { body: {} }).subscribe();
    requests.expectNone(`${base}/api/admin/example`);
    bootstrap();
    const request = requests.expectOne(`${base}/api/admin/example`);
    expect(request.request.headers.get(header)).toBe('framework-test-proof');
    expect(request.request.withCredentials).toBe(true);
    request.flush({});
  });

  it('supports already absolute API URLs used by authentication', () => {
    const absolute = new URL(`${base}/api/identity/login`, window.location.origin).href;
    http.post(absolute, {}).subscribe();
    bootstrap();
    const request = requests.expectOne(absolute);
    expect(request.request.headers.get(header)).toBe('framework-test-proof');
    request.flush({});
  });

  it.each(['GET', 'HEAD', 'OPTIONS'])('leaves %s requests unaffected', method => {
    http.request(method, '/api/admin/example').subscribe();
    const request = requests.expectOne(`${base}/api/admin/example`);
    expect(request.request.headers.has(header)).toBe(false);
    request.flush(null);
    requests.expectNone(tokenUrl);
  });

  it.each([
    'https://untrusted.example.test/api/admin/example',
    'https://api.example.test.untrusted.test/api/admin/example',
    '//untrusted.example.test/api/admin/example',
    '/unrelated-action',
  ])('never sends proof to an unrelated destination: %s', url => {
    http.post(url, {}).subscribe();
    const request = requests.expectOne(url);
    expect(request.request.headers.has(header)).toBe(false);
    expect(request.request.withCredentials).toBe(false);
    request.flush({});
    requests.expectNone(tokenUrl);
  });

  const explicitCredentials: Record<string, string>[] = [
    { Authorization: 'Bearer test-access' },
    { Authorization: 'Device test-device' },
    { 'X-Platform-Client-Key': 'test-client', 'X-Platform-Client-Secret': 'test-secret' },
  ];
  it.each(explicitCredentials)('does not impose browser proof on explicit credential clients', values => {
    http.post('/api/mobile/example', {}, { headers: new HttpHeaders(values) }).subscribe();
    const request = requests.expectOne(`${base}/api/mobile/example`);
    expect(request.request.headers.has(header)).toBe(false);
    request.flush({});
    requests.expectNone(tokenUrl);
  });

  it('shares one bootstrap for concurrent requests and reuses the current proof', () => {
    http.post('/api/admin/first', {}).subscribe();
    http.post('/api/admin/second', {}).subscribe();
    bootstrap();
    requests.expectOne(`${base}/api/admin/first`).flush({});
    requests.expectOne(`${base}/api/admin/second`).flush({});
    http.post('/api/admin/third', {}).subscribe();
    const third = requests.expectOne(`${base}/api/admin/third`);
    expect(third.request.headers.get(header)).toBe('framework-test-proof');
    third.flush({});
    requests.expectNone(tokenUrl);
  });

  it.each(['login', 'logout'])('refreshes identity-bound proof after %s', operation => {
    http.post(`${base}/api/identity/${operation}`, {}).subscribe();
    bootstrap('old-test-proof');
    requests.expectOne(`${base}/api/identity/${operation}`).flush(null);
    http.post('/api/admin/example', {}).subscribe();
    bootstrap('new-test-proof');
    const request = requests.expectOne(`${base}/api/admin/example`);
    expect(request.request.headers.get(header)).toBe('new-test-proof');
    request.flush({});
  });

  it('clears rejected proof without automatically replaying a mutation', () => {
    let failed = false;
    http.post('/api/admin/example', {}).subscribe({ error: () => { failed = true; } });
    bootstrap();
    requests.expectOne(`${base}/api/admin/example`).flush(
      { code: 'antiforgery_validation_failed' }, { status: 400, statusText: 'Bad Request' },
    );
    expect(failed).toBe(true);
    requests.expectNone(tokenUrl);
    requests.expectNone(`${base}/api/admin/example`);
    http.post('/api/admin/example', {}).subscribe();
    bootstrap('fresh-test-proof');
    requests.expectOne(`${base}/api/admin/example`).flush({});
  });

  it('does not send a mutation if token bootstrap fails, and allows a later fresh bootstrap', () => {
    http.post('/api/admin/example', {}).subscribe({ error: () => {} });
    requests.expectOne(tokenUrl).flush(null, { status: 503, statusText: 'Unavailable' });
    requests.expectNone(`${base}/api/admin/example`);
    http.post('/api/admin/example', {}).subscribe();
    bootstrap();
    requests.expectOne(`${base}/api/admin/example`).flush({});
  });
});
