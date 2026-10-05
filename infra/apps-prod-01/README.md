# apps-prod-01 deployment

This stack runs the Bot Global frontend, API, and PostgreSQL canary database on
`apps-prod-01`.

## Backend images

The backend service can still be built locally as a fallback, but production
deployment should use a prebuilt image:

```bash
BOTGLOBAL_BACKEND_IMAGE=ghcr.io/faragashraf/bot-global-platform/botglobal-backend:sha-<commit> \
docker compose pull backend
docker compose up -d --no-build backend caddy
```

Use `scripts/deploy-pulled-image.sh` on the server once
`BOTGLOBAL_BACKEND_IMAGE` is set in the deployment environment.

## PostgreSQL backups

Run `scripts/backup-postgres.sh` from this folder. It writes compressed
`pg_dumpall` backups to:

```text
/home/deploy/backups/bot-global-platform/postgres
```

The default retention is 14 days. Override with `RETENTION_DAYS=<days>`.

## Data Protection

The backend persists ASP.NET Data Protection keys in the
`data-protection-keys` Docker volume and protects them with the local
certificate mounted at:

```text
./secrets/data-protection.pfx
```

The certificate password is supplied by `DATA_PROTECTION_CERTIFICATE_PASSWORD`
in the server environment. Do not commit the certificate or password.
