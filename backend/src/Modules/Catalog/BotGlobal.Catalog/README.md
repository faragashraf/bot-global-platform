# Catalog persistence configuration

The API expects the Catalog SQL Server connection string at `ConnectionStrings:Catalog`.
For local or deployed environments, supply it through a protected configuration provider;
for example, the environment variable name is `ConnectionStrings__Catalog`. The checked-in
development value is a credential-free local placeholder and must be replaced for the actual
SQL Server instance.

Catalog migrations and the `__EFMigrationsHistory` table are isolated in the `catalog` schema.
