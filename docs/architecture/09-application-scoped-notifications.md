# Application-scoped notifications

Notifications are a shared Bot Global capability. Lamma, ENPO Connect, and
future applications use the same semantic notification contracts and
processing infrastructure, but application data and provider routes are never
shared implicitly.

## Authoritative application context

`NotificationApplicationContext` is created from server-owned identity:

- machine notification requests use the authenticated platform-client claim;
- mobile push registration uses the device and application claims produced by
  the server-side device authenticator;
- campaign administrators may select an application, because Administrator is
  currently a Bot Global platform-wide role, but the selection is resolved
  through `IPlatformClientDescriptorReader` and the returned identifier must
  exactly match an active application;
- campaign workers recreate the context from the persisted campaign
  `PlatformClientId`, not from a delivery request.

The context is mandatory for audience reads, recipient resolution, current
device checks, push-destination lookup, semantic delivery, delivery-log reads,
and application-filtered administration. An absent admin filter is represented
explicitly as `ApplicationAdministrationScope.PlatformGlobal`; it is not
interpreted as an accidental unscoped query.

## Provider boundary

The delivery flow is:

```text
semantic notification
  -> validated NotificationApplicationContext
  -> application push-provider resolver
  -> application/provider configuration metadata
  -> provider-neutral dispatcher
  -> FCM runtime (APNs runtime is deferred)
```

Provider routes are keyed by the exact `(ApplicationId, Provider)` pair. A
route records enabled/disabled state, a server-side configuration reference,
Firebase project and Android package identity where applicable, and an Apple
bundle identity for a future APNs runtime. Missing and disabled routes produce
semantic, non-delivery outcomes. They do not fall back to another
application's route.

The current Firebase Admin runtime remains deliberately single-profile until
the live multi-project delivery slice. It can send only when its
`ApplicationId`, `ConfigurationReference`, and project identity match the
resolved route. A mismatch fails safely. Firebase SDK types and credentials
remain inside Communication and are not referenced by Notifications, Pairing,
or business DTOs.

## Persistence ownership

The Notifications database owns:

- `notifications.NotificationCampaigns`, including authoritative
  `PlatformClientId` and application key/display snapshots;
- `notifications.NotificationRecipients`, linked to exactly one campaign and
  unique per campaign/device;
- durable attempt status, retry/lease fields, safe error codes, and aggregate
  campaign counts stored on those two tables.

There are currently no notification inbox or preference tables. Delivery logs
and summaries are projections of campaign/recipient data.

The Pairing database owns:

- pairing challenges and state;
- mobile devices, including authoritative `PlatformClientId`;
- push registrations linked to one mobile device;
- device lifecycle/audit entries, each retaining `PlatformClientId`.

The same installation identifier in two Bot Global applications is represented
by two device rows because uniqueness is `(PlatformClientId, InstallationId)`.
Push registrations are not merged across those rows. No table moved between
databases in this slice, and no migration was added or applied.

## Administration and secrets

Administrator currently means Bot Global platform administrator. That role may
use an explicit platform-global scope or a validated application scope. This
slice does not introduce per-application administrator entitlements.

Admin campaign/device DTOs do not contain registration tokens, provider
configuration references, Firebase project data, credential paths, or secrets.
Firebase credentials are server-only and must be supplied through deployment
configuration or a secret-backed path. Logging includes application IDs and
safe provider error codes only; it does not log registration tokens or
credential contents.

## Configuration names

Required database configuration:

- `ConnectionStrings__Notifications`
- `ConnectionStrings__Pairing`

Optional provider routing configuration (required per enabled route):

- `Notifications__PushProviders__DefaultTimeToLiveDays`
- `Notifications__PushProviders__Providers__{n}__ApplicationId`
- `Notifications__PushProviders__Providers__{n}__Provider`
- `Notifications__PushProviders__Providers__{n}__Enabled`
- `Notifications__PushProviders__Providers__{n}__ConfigurationReference`
- `Notifications__PushProviders__Providers__{n}__FirebaseProjectId`
- `Notifications__PushProviders__Providers__{n}__AndroidPackageName`
- `Notifications__PushProviders__Providers__{n}__AppleBundleId`

The provider value currently recognizes `fcm` and reserves `apns`. Provider-
specific identity fields are required only for an enabled matching provider.

Optional Firebase runtime configuration (all except `Enabled` are required
when enabled):

- `Firebase__Enabled`
- `Firebase__ApplicationId`
- `Firebase__ConfigurationReference`
- `Firebase__ProjectId`
- `Firebase__CredentialPath`

Campaign processing configuration remains:

- `Notifications__DefaultCampaignLifetimeDays`
- `Notifications__MinimumCampaignLifetimeDays`
- `Notifications__MaximumCampaignLifetimeDays`
- `Notifications__Worker__BatchSize`
- `Notifications__Worker__PollIntervalSeconds`
- `Notifications__Worker__LeaseSeconds`
- `Notifications__Worker__MaxParallelDeliveries`
- `Notifications__Retry__InitialDelaySeconds`
- `Notifications__Retry__MaximumDelayMinutes`

The frontend runtime setting `window.__BOT_GLOBAL_CONFIG__.apiBaseUrl` is
public and contains no provider credentials.

## Deferred

Live multi-project Firebase delivery, APNs runtime, production provider
credentials, production migrations, deployment verification, campaign worker
redesign, zero-recipient dispatch behavior, transport-success/persistence-
failure deduplication, and cross-database history purge consistency remain
separate capability slices.
