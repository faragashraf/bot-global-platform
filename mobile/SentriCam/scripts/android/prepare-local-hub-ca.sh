#!/bin/sh
set -eu

usage() {
    echo "Usage: $0 [--ca PUBLIC_ROOT_CA] [--output GENERATED_DER]" >&2
    exit 64
}

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository_root=$(CDPATH= cd -- "$script_dir/../.." && pwd -P)
ca_path=${SENTRICAM_CADDY_ROOT_CA:-}
output_path="$repository_root/androidApp/build/generated/sentricamLocalCa/debug/res/raw/sentricam_local_ca.der"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --ca) ca_path=${2-}; shift 2 ;;
        --output) output_path=${2-}; shift 2 ;;
        *) usage ;;
    esac
done

if [ -z "$ca_path" ]; then
    user_home=${HOME:?HOME must identify the current developer account}
    ca_path="$user_home/Library/Application Support/Caddy/pki/authorities/local/root.crt"
fi

if [ ! -f "$ca_path" ]; then
    echo "SentriCam local CA is missing: $ca_path" >&2
    echo "Set SENTRICAM_CADDY_ROOT_CA or -Psentricam.caddyRootCa to the public Caddy Root CA certificate." >&2
    exit 66
fi

if LC_ALL=C grep -aEq -- '-----BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY-----' "$ca_path"; then
    echo "Refusing local CA input that contains private key material: $ca_path" >&2
    exit 65
fi

mkdir -p "$(dirname "$output_path")"
temporary_der=$(mktemp "${output_path}.tmp.XXXXXX")
temporary_pem=$(mktemp "${output_path}.pem.XXXXXX")
cleanup() {
    rm -f "$temporary_der" "$temporary_pem"
}
trap cleanup EXIT HUP INT TERM

if openssl x509 -in "$ca_path" -noout >/dev/null 2>&1; then
    certificate_count=$(LC_ALL=C grep -ac -- '-----BEGIN CERTIFICATE-----' "$ca_path")
    if [ "$certificate_count" -ne 1 ]; then
        echo "SentriCam local CA input must contain exactly one certificate." >&2
        exit 65
    fi
    openssl x509 -in "$ca_path" -outform DER -out "$temporary_der"
elif openssl x509 -inform DER -in "$ca_path" -noout >/dev/null 2>&1; then
    openssl x509 -inform DER -in "$ca_path" -outform DER -out "$temporary_der"
else
    echo "SentriCam local CA input is not a valid X.509 certificate: $ca_path" >&2
    exit 65
fi

certificate_text=$(openssl x509 -inform DER -in "$temporary_der" -noout -text)
if ! printf '%s\n' "$certificate_text" | LC_ALL=C grep -q 'CA:TRUE'; then
    echo "SentriCam local CA input is not an X.509 CA certificate." >&2
    exit 65
fi
if ! openssl x509 -inform DER -in "$temporary_der" -checkend 0 -noout >/dev/null; then
    echo "SentriCam local CA certificate is expired or not currently valid." >&2
    exit 65
fi

subject=$(openssl x509 -inform DER -in "$temporary_der" -noout -subject -nameopt RFC2253)
issuer=$(openssl x509 -inform DER -in "$temporary_der" -noout -issuer -nameopt RFC2253)
if [ "${subject#subject=}" != "${issuer#issuer=}" ]; then
    echo "SentriCam local CA input must be a self-signed Root CA certificate." >&2
    exit 65
fi

openssl x509 -inform DER -in "$temporary_der" -out "$temporary_pem"
if ! openssl verify -CAfile "$temporary_pem" "$temporary_pem" >/dev/null 2>&1; then
    echo "SentriCam local CA self-signature validation failed." >&2
    exit 65
fi

chmod 0644 "$temporary_der"
mv -f "$temporary_der" "$output_path"
trap - EXIT HUP INT TERM
rm -f "$temporary_pem"

fingerprint=$(openssl x509 -inform DER -in "$output_path" -noout -fingerprint -sha256)
printf 'Prepared Debug-only public SentriCam local CA: %s\n%s\n' "$output_path" "$fingerprint"
