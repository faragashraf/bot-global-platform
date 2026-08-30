# Bot Global Platform

A bilingual (Arabic/English), theme-aware digital platform for Bot Global.

## Stack
- Angular 21
- PrimeNG 21
- ASP.NET Core / .NET 10
- SQL Server (introduced with persistence slice)
- SignalR (Realtime capability)

## Architecture
- Frontend: Core + Shared UI + Capability Features
- Backend: Modular Monolith by Capability

## Current scope
Foundation only. Existing products such as WhatsApp SaaS are intentionally
not merged into this repository yet.

## Validate
```bash
cd frontend
npm run build

cd ../backend
dotnet build BotGlobal.sln
dotnet test BotGlobal.sln
```

## Run locally
Start SQL Server first, then from the repository root run:

```bash
./dev.sh
```

The Angular app runs at `http://localhost:4200` and proxies `/api` to the
ASP.NET Core API at `http://localhost:5062`. Press `Ctrl+C` to stop both.
