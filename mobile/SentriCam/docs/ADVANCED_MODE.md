# Advanced mode

Office and Enterprise retain the product-led base journey and add explicit infrastructure controls for an administrator.

## Database

The administrator can choose:

- built-in SQLite;
- SQL Server;
- PostgreSQL;
- server, port, database, username, and password;
- encryption and SQL Server integrated-security options;
- connection test before configuration.

Passwords are written only to the protected secret document. A resumed draft reports that a password is stored but never returns its value.

PostgreSQL is exposed as an advanced provider foundation in v0.3.0, not as a production-certified deployment claim. See [Database providers](DATABASE_PROVIDERS.md) for its validation boundary.

## Network and certificates

Advanced setup can enable HTTPS, select a port, generate a self-signed Hub certificate, or reference a supplied PFX. Generated material is protected and activates on the next Hub start. The wizard clearly reports this restart boundary.

Certificate trust installation, DNS/mDNS registration, firewall changes, reverse proxies, and managed certificate enrollment are deployment or future-installer concerns. The wizard does not claim those host mutations occurred.

## Storage and media

Advanced storage exposes explicit retention days and reserved free space. Administrators can keep the Hub-managed media engine or select a custom FFmpeg executable. Detection reports installed, missing, or broken without preventing unrelated Hub capabilities from starting.

## Operational guidance

- test the external provider before configuring;
- use least-privilege database accounts;
- keep encryption enabled across hosts;
- back up settings, secrets, database, and recordings together;
- use managed certificates in shared or Enterprise networks;
- do not expose the local-session endpoint beyond trusted network boundaries.
