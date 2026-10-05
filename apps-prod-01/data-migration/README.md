# Bot Global Data Migration

One-shot SQL Server to PostgreSQL migration utility for `apps-prod-01`.

The tool is intentionally separate from the backend runtime. It reads the old
SQL Server databases and rebuilds the PostgreSQL canary databases from scratch.

Required environment variables:

- `SOURCE_IDENTITY_CONNECTION`
- `SOURCE_SHARED_CONNECTION`
- `TARGET_POSTGRES_HOST`
- `TARGET_POSTGRES_PORT`
- `TARGET_POSTGRES_USER`
- `TARGET_POSTGRES_PASSWORD`

Default mode is dry-run:

```bash
dotnet run --project apps-prod-01/data-migration
```

Execution mode resets target PostgreSQL databases:

```bash
dotnet run --project apps-prod-01/data-migration -- --execute
```
