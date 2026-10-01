# Database providers

## Strategy

SentriCam supports SQLite, SQL Server, and PostgreSQL through one provider selector around one Entity Framework Core context.

~~~text
Hub setup choice
      │
      ▼
DatabaseProviderConfigurator
      │
      ▼
SentriCamDbContext
      │
      ├── Device repositories
      ├── Recording repository
      ├── Outbox
      └── Unit of work
~~~

There are no provider-specific business services or duplicate repositories.

## Provider matrix

| Provider | Intended mode | Default port | Migration assembly |
| --- | --- | ---: | --- |
| SQLite | Home | none | SentriCam.Migrations.Sqlite |
| SQL Server | Office / Enterprise | 1433 | SentriCam.Infrastructure |
| PostgreSQL | Office / Enterprise | 5432 | SentriCam.Migrations.PostgreSql |

PostgreSQL in v0.3.0 is a provider foundation: selection, connection building, EF configuration, and a provider-native migration assembly are present. It is not yet a production-certified deployment target; live-server migration, upgrade, backup/restore, failover, and sustained-load validation remain release work for a PostgreSQL deployment profile.

## SQLite strategy

The Home database lives at Hub home/data/sentricam.db with shared cache and foreign keys enabled. The setup engine verifies that the containing folder is writable, creates it, and runs provider-native migrations.

SQLite and PostgreSQL use application-stamped random concurrency bytes. SQL Server retains native rowversion. This distinction stays inside persistence and does not alter domain behavior.

## Migrations

Provider-native histories are necessary because types, filtered-index SQL, and concurrency behavior differ. New model changes must generate and review migrations for all three providers in the same capability change.

Validation should include:

1. model and architecture tests;
2. SQLite migration from an empty file;
3. repository tests against the common context;
4. SQL Server and PostgreSQL integration migration checks in environments that provide those services;
5. startup reapplication of pending migrations.

## Adding a provider

A new provider requires a supported provider constant, EF provider package, configurator branch, dedicated migration assembly, provisioning/connection-string support, status label, advanced UI choice, tests, and documentation. It must not add a second context or copy repository logic.
