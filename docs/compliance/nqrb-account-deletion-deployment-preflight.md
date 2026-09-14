# NQRB account-deletion migration preflight

Migration: `20260913214943_AddNqrbAccountDeletionOperations`

Run this read-only check against the target Identity database before approving the migration. It verifies that the new `ApplicationMemberships.GlobalUserId -> Users.Id` foreign key will not encounter an existing orphan. The expected result is `0`.

```sql
SELECT COUNT_BIG(*) AS OrphanedApplicationMembershipCount
FROM [identity].[ApplicationMemberships] AS membership
LEFT JOIN [identity].[Users] AS app_user
    ON app_user.[Id] = membership.[GlobalUserId]
WHERE membership.[GlobalUserId] IS NOT NULL
  AND app_user.[Id] IS NULL;
```

If the result is not zero, stop the migration. Investigate the orphaned memberships and obtain a separate, reviewed remediation plan. Do not delete or rewrite those rows as part of this preflight.

This document does not authorize connecting to production or applying the migration.

Before deploying the public web-deletion flow, configure:

- `Identity__Federated__Google__NqrbWebClientId` with the public Web OAuth Client ID declared in `privacy-site/assets/nqrb-config.js`.
- `Frontend__AllowedOrigins` so the deployed array preserves existing valid origins and includes the GitHub Pages origin declared for NQRB. When an environment provider replaces arrays by index, preserve the localhost/development entry only in development configuration and supply the production Pages origin at the appropriate production index.

No Google client secret or redirect URI is used by the GIS popup/callback flow.

Operational confirmation still required: `CALLING_HORIZONTAL_SCALE_CONFIRMATION_REQUIRED`. The repository does not establish whether the production Calling runtime runs as one process or multiple horizontally scaled processes.
