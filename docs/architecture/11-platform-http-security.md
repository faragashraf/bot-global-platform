# Platform HTTP security

`BotGlobal.Api.Security.PlatformHttpSecurity` owns the browser antiforgery
convention. Register its services after Identity, and run its middleware after
authentication and authorization. Keep it before endpoint execution.

## Authentication boundary

The convention validates POST, PUT, PATCH, and DELETE when the authentication
ticket selected for the request includes `Identity.Application` and that cookie
actually authenticated. It uses ASP.NET Core `IAntiforgery.ValidateRequestAsync`.
A supplied Authorization header does not exempt a cookie-authorized operation.
Authorization still returns 401/403 before antiforgery validation.

| Current surface | Authentication | Browser proof |
| --- | --- | --- |
| `/api/admin/catalog/products` create/update | Administrator cookie | Required |
| `/api/admin/platform-clients` create, capability update, credential rotation/revocation | Administrator cookie | Required |
| `/api/admin/device-pairings/{deviceId}/revoke` | Administrator cookie | Required |
| `/api/admin/notification-campaigns` create/cancel | Administrator cookie | Required |
| `/api/identity/logout` | Application cookie | Required |
| `/api/identity/login` | Anonymous JSON login; may have an existing cookie | Required when a cookie authenticates; frontend bootstraps for either case |
| Mobile application session policies | `BotGlobal.MobileSession` opaque Bearer | Not required |
| Mobile device policies | `MobileDevice` credential handler | Not required |
| Platform client policies, including direct semantic notifications | Platform client key/secret handler | Not required |
| SignalR negotiation/transports | Hub authentication | Excluded using framework `HubMetadata` |
| GET/HEAD/OPTIONS | Existing endpoint policy | Not required |

No mutating GET was identified in the current browser/admin endpoint inventory.
New cookie-authorized mutation endpoints automatically participate in the
convention; they do not need a product-specific filter or copied validation.

## Browser bootstrap

`GET /api/security/antiforgery` calls `IAntiforgery.GetAndStoreTokens`, sets the
framework cookie, and returns `{ "requestToken": "..." }` with `Cache-Control:
no-store`. The request token is sent in `X-XSRF-TOKEN`. Tokens are framework
generated and bound to the antiforgery cookie and authenticated identity.
Invalid/missing proof returns a generic 400 problem with code
`antiforgery_validation_failed`, without validation details or token values.

Production cookies remain host-only, Path `/`, Secure, HttpOnly, SameSite=None:

- Existing authentication: `__Host-BotGlobal.Admin` (behavior preserved).
- New antiforgery: `__Host-BotGlobal.Antiforgery`.

Development uses `BotGlobal.Antiforgery.Development`, SameSite=Lax and
SecurePolicy=SameAsRequest so the existing local HTTP proxy can bootstrap.
This changes only the new antiforgery cookie; the authentication cookie remains
Secure and HttpOnly. Production, Staging, and other environments use the secure
antiforgery configuration.

## Angular integration

The runtime API base URL supports a separately hosted API. Angular's native
cookie extractor cannot read an API-origin cookie from that frontend. One
central `browserAntiforgeryInterceptor` therefore uses the framework's JSON
bootstrap for both same-origin and separate-origin layouts, and the default
cookie extractor is disabled with `withNoXsrfProtection` to avoid competing
implementations. See [Angular XSRF support](https://angular.dev/best-practices/security)
and [ASP.NET Core antiforgery](https://learn.microsoft.com/en-us/aspnet/core/security/anti-request-forgery?view=aspnetcore-10.0).

The interceptor only attaches proof to mutations under the configured API
origin and path. Explicit bearer/device/machine credential requests and foreign
URLs do not acquire a browser proof requirement. No per-service header logic is
needed. Bootstrap uses credentials and bypasses interceptors to avoid recursion;
the token stays in memory. Concurrent mutations share one bootstrap.

Successful login/logout, 401, and antiforgery rejection clear the cached proof.
Failed mutations are never automatically replayed. The next user action obtains
fresh proof. A failed bootstrap prevents the dependent mutation from being sent.
Cross-origin deployments must retain their explicit `Frontend:AllowedOrigins`
allowlist and credential support; do not replace it with reflected/wildcard
origins. Authentication cookies are never exposed to JavaScript.

## Diagnostic messaging

`POST /api/communication/test/send-to-user` is mapped only in Development. It
remains authenticated and subject to cookie antiforgery there. It is absent in
Production, Staging, Test, and unknown environments. This is a temporary SignalR
transport diagnostic that accepts a target user identifier, not a production
FCM or notification campaign endpoint.

`POST /api/mobile-notifications` retains its machine capability and trusted
application scope. Provider selection, FCM priority, and the independent
notification campaign worker setting are unchanged.

## Validation

`PlatformHttpSecurityTests` exercises the real HTTP middleware, Identity cookie,
mobile session/device/machine handlers, admin rotation endpoint, communication
route registration, and SignalR negotiation through TestServer. Credential
lookups and delivery boundaries use test doubles; no database or provider is
contacted. The control case without the convention reproduces the original
cross-site form mutation. Angular interceptor tests cover both deployment
layouts, origin boundaries, session changes, concurrency, and failure handling.
