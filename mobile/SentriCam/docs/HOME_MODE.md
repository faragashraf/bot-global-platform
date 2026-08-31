# Home mode

Home is the default and recommended SentriCam Hub experience.

## User choices

The user sees only:

- Hub name;
- recording folder;
- Balanced, Keep more, or Space saver policy;
- Start SentriCam automatically;
- Open dashboard when ready.

The UI does not render SQLite, database provider, connection string, JWT, migration, ASP.NET, FFmpeg path, or port controls.

## Automatic choices

SentriCam:

- selects SQLite and a private database file under the Hub home;
- creates the data and recording folders;
- applies SQLite migrations;
- generates random signing and pairing secrets;
- stores public settings separately from secrets;
- uses automatic local networking defaults;
- prefers the packaged media engine and detects a development fallback;
- records startup and dashboard preferences.

The dashboard describes SQLite as private Hub data. Missing media support is presented as a capability state rather than an installation task.

## Storage policies

| Policy | Default retention | Reserved free space | Intent |
| --- | ---: | ---: | --- |
| Balanced | 30 days | 10 GB | Everyday default |
| Keep more | 90 days | 20 GB | Longer local history |
| Space saver | 7 days | 15 GB | Smaller disks |

v0.3.0 persists these policies and reports storage health. Automated deletion/retention enforcement is a later storage-engine capability and must reuse this configuration.

## Security

The generated secrets never appear in the Home wizard or dashboard. Android pairs through a one-time QR rather than manual host, port, or token fields. Release packaging must complete trusted HTTPS distribution as documented in the installer roadmap.
