# Android scripts

## Local Hub HTTPS for Debug builds

`prepare-local-hub-ca.sh` validates and converts only the public Caddy Root CA into
an ignored generated Android resource. `:SentriCam:androidApp:preDebugBuild` runs it automatically;
Release variants neither run the task nor include the generated resource or Debug
network-security configuration.

On macOS, the default source is discovered from the current user's Caddy storage:

```text
$HOME/Library/Application Support/Caddy/pki/authorities/local/root.crt
```

Override it with either:

```sh
SENTRICAM_CADDY_ROOT_CA=/path/to/public-root.crt mobile/gradlew -p mobile :SentriCam:androidApp:assembleDebug
mobile/gradlew -p mobile :SentriCam:androidApp:assembleDebug -Psentricam.caddyRootCa=/path/to/public-root.crt
```

The source must be one current, self-signed X.509 CA certificate. Missing, invalid,
expired, non-CA, or private-key-bearing input fails the Debug preparation. The output
is DER-encoded under `androidApp/build/generated/sentricamLocalCa/debug/res/raw/`
and is ignored as generated build output.

Run the focused preparation checks with:

```sh
mobile/gradlew -p mobile :SentriCam:androidApp:testSentriCamLocalCaPreparation
```
