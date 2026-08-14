# Platform Client Authentication

Platform Client Authentication provides generic machine-to-machine identity for
trusted external systems calling the Bot Global public platform.

It is not tied to Connect V2, Pairing, Communication, or any organization.

Human users remain in `BotGlobal.Identity`. Machine/service clients are modeled
independently in `PlatformClients`.

## Foundation model

- `PlatformClient`: registered external system.
- `PlatformClientCredential`: revocable/expiring machine credential.
- `PlatformClientCapability`: generic capability/scope grant.

Examples of capabilities:

```text
pairing:create
pairing:status
communication:publish
notifications:publish
```

## Secret security

Generated client secrets contain 256 bits of random entropy.

Bot Global persists only:

```text
SHA-256(secret) -> binary(32)
```

Verification uses constant-time comparison. Plaintext secrets are returned only
at creation time and are never modeled for persistence.

## Persistence boundary

Planned schema:

```text
platform_clients
```

Tables:

```text
Clients
Credentials
Capabilities
```

There are no database foreign keys to Human Identity, Communication,
Notifications, Catalog, Oracle, or organization directories.

## Deferred from this slice

- runtime DbContext registration;
- connection string;
- migration;
- database write;
- authentication middleware/handler;
- HTTP endpoints;
- pairing;
- mobile sessions;
- access/refresh tokens.

The next gate is migration generation/static audit after this domain/model is
reviewed.

## Independent deployment instances

The same backend capability code may be deployed into multiple independent
instances.

PlatformClients owns:

```text
ConnectionStrings:PlatformClients
platform_clients.__EFMigrationsHistory
```

The physical SQL Server/database may be shared today while the configuration
key and migration history remain isolated. A future internal deployment may
point the same key to another database without changing domain/application code.

## Database provisioning policy

New instances are provisioned through owned EF Core migrations.

`EnsureCreated` is not used.

Normal application startup must not silently mutate production databases.

Default policy:

```text
automatic migration on ordinary startup = disabled
```

Deployment/bootstrap tooling may apply module-owned migrations explicitly after
connection strings are configured and before the instance is enabled.

## Runtime persistence configuration

PlatformClients runtime persistence uses only:

```text
ConnectionStrings:PlatformClients
```

`PlatformClientsDbContext` is registered by the PlatformClients module itself.

SQL Server runtime configuration uses:

```text
schema: platform_clients
migration history: platform_clients.__EFMigrationsHistory
```

The connection string is independent from Identity, Catalog, Communication,
Notifications, and organization-specific databases.

The module fails fast during application startup when its dedicated connection
string is absent.

Runtime registration does not automatically create, migrate, or mutate the
database. Migration application remains an explicit deployment/bootstrap step.

For a future independent instance, only configuration/database targets change;
the PlatformClients domain/application code remains the same.
