# Public Catalog boundary

## Current

`InMemoryCatalogRepository` reads the temporary seed data and publishes it through the asynchronous `CatalogRepository` contract. `CatalogEngine` validates and indexes that stream, then exposes loading/error state and catalog selectors to Home, category pages, detail pages, and shared catalog UI.

```text
In-memory seed repository -> Catalog Engine -> Public UI
```

Product-specific English and Arabic content stays with each product. Media is represented only by URL/reference metadata, and product actions are typed link records; no binary media is stored in the frontend model.

## Future

An HTTP repository/client can replace the in-memory provider behind `CATALOG_REPOSITORY` without changing the engine or public UI.

```text
Catalog API -> HTTP repository/client -> Catalog Engine -> Same Public UI
```

Future backend persistence may normalize Products, ProductLocalizations, ProductMedia, ProductLinks, and ProductReleases. Media files belong in a filesystem, object store, or CDN; persistence stores only their metadata and references.

Backend, database, API, migrations, media storage, admin UI, and authentication work are intentionally out of scope for this frontend branch.
