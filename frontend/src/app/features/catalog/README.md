# Public Catalog boundary

## Current

`HttpCatalogRepository` requests the public product collection once through the asynchronous `CatalogRepository` contract. It validates the unknown transport payload and maps it to the existing catalog model. `CatalogEngine` validates and indexes that stream, then exposes loading/error state and catalog selectors to Home, category pages, detail pages, and shared catalog UI.

```text
GET /api/catalog/products -> HTTP repository -> Catalog Engine -> Public UI
```

Product-specific English and Arabic content stays with each product. Media is represented only by URL/reference metadata, and product actions are typed link records; no binary media is stored in the frontend model.

The detail page deliberately selects from the canonical collection stream. This keeps one request and preserves the existing engine/page architecture; the detail endpoint remains available for a future direct-entry optimization if catalog size makes that useful.

`InMemoryCatalogRepository` and `catalog.data.ts` remain only for focused unit tests and isolated test setup. The default application provider is HTTP-backed and never falls back to seed data after an HTTP or mapping failure.

## API configuration and local development

`environment.apiBaseUrl` is intentionally empty, so deployed builds use the same-origin `/api` path without embedding a production hostname. `ng serve` uses `proxy.conf.json` to forward `/api` to the backend development HTTP endpoint at `http://localhost:5062`; this avoids broad backend CORS rules.

Production deployment must route the frontend origin's `/api` path to `BotGlobal.Api`. If production later requires a separate API origin, add a deployment-specific environment replacement for `apiBaseUrl` and configure only that exact origin in backend CORS.

Backend schema, migrations, media storage, admin UI, and authentication are outside this slice.
