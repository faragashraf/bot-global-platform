# SentriCam Server

This directory is the complete .NET 8 solution root. It contains central build/package configuration, source projects, tests, the SDK pin, and the repository-local .NET tool manifest.

```bash
dotnet restore
dotnet build
dotnet test
```

The API can start without pre-supplied infrastructure configuration. First-run setup selects SQLite automatically for Home, while Office and Enterprise can use SQLite, SQL Server, or PostgreSQL through the same context and repositories. The Hub creates and protects runtime secrets, applies provider-native migrations, and resumes incomplete setup from its file-backed state.

See [setup experience](../docs/SETUP_EXPERIENCE.md), [database providers](../docs/DATABASE_PROVIDERS.md), [server architecture](../docs/SERVER.md), and [deployment](../docs/DEPLOYMENT.md).
