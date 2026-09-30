# Bot Global Service Backend Subapplication

The public Bot Global API base for current client rebuilds is:

```text
https://botglobalservice.com/backend
```

Deploy `BotGlobal.Api` as an IIS ASP.NET Core subapplication mounted at `/backend`.
API and SignalR routes remain the application-relative routes already mapped by the
ASP.NET Core app, such as `/api/...`, `/hubs/calling`, `/hubs/games`, and `/health`.

Runtime prerequisites:

- IIS has the ASP.NET Core Hosting Bundle and .NET 10 runtime available.
- The IIS application is mounted at `/backend` under `botglobalservice.com`.
- Production configuration supplies real connection strings and provider settings
  outside source control.
- Current installed mobile binaries keep using their embedded endpoint until each
  app is rebuilt, tested, and released.

The website's root `web.config` must come from `frontend/public/web.config` so SPA
fallback leaves `/backend` to the IIS child application. The deployed root
`runtime-config.js` must come from `frontend/deploy/botglobalservice/runtime-config.js`;
the generic frontend build keeps its local default empty. Check `/backend/health`
returns an API response rather than the frontend HTML before releasing clients.
