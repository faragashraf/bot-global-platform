# Installer roadmap

v0.3.0 intentionally does not build an MSI, PKG, EXE, signed desktop bundle, or background service installer. It establishes the product runtime those packages will launch.

## What v0.3.0 prepares

- first-run setup and resume behavior;
- stable Hub home and runtime file layout;
- automatic data provisioning and migrations;
- generated secrets and certificate material;
- startup preference;
- static dashboard hosting hook;
- FFmpeg packaged-path detection;
- LAN pairing payload and Android deep link;
- health checks needed by a launcher.

## Packaging sequence

### 1. Common publish layout

Publish the .NET API, compiled web assets, provider migration assemblies, launcher metadata, license notices, and a platform-specific FFmpeg binary into one versioned application directory.

### 2. Host integration

Add per-platform launchers that:

- choose the correct writable Hub home;
- start the Hub at login when requested;
- open the dashboard when requested;
- restart after setup when HTTPS activation requires it;
- provide safe upgrade and rollback;
- never run the application data directory as an executable directory.

### 3. Network trust

Implement one approved release design:

- locally trusted Hub CA installed with informed consent;
- certificate enrollment through an administrator-managed PKI;
- or Android certificate pinning with a fingerprint carried by a signed pairing payload.

Also add local hostname discovery and narrowly scoped firewall rules. Do not weaken Android cleartext policy.

### 4. Platform packages

- Windows: signed MSI or MSIX, service/startup integration, firewall rule, uninstall preserving user data by default.
- macOS: signed and notarized PKG/app, LaunchAgent, Keychain certificate handling, Local Network privacy messaging.
- Linux: documented package/service path after Windows and macOS behavior stabilizes.

### 5. Release validation

Test clean install, interrupted setup, resume, upgrade with migrations, downgrade refusal, service restart, certificate renewal, FFmpeg license/artifact integrity, firewall behavior, pairing on physical Android, repair, and uninstall/reinstall with preserved data.

## Exit criteria

The installer milestone is complete only when a non-technical user can download a signed package, install without a terminal, finish Home setup, pair a release Android device over trusted HTTPS, survive reboot and upgrade, and uninstall with an explicit data-retention choice.
