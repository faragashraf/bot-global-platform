#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
preparer="$script_dir/prepare-local-hub-ca.sh"
test_root=$(mktemp -d /tmp/sentricam-local-ca-test.XXXXXX)
cleanup() {
    case "$test_root" in
        /tmp/sentricam-local-ca-test.*) rm -rf "$test_root" ;;
    esac
}
trap cleanup EXIT HUP INT TERM

expect_failure() {
    expected_message=$1
    shift
    if "$@" >"$test_root/stdout.log" 2>"$test_root/stderr.log"; then
        echo "Expected command to fail: $*" >&2
        exit 1
    fi
    if ! grep -q "$expected_message" "$test_root/stderr.log"; then
        echo "Failure did not contain expected diagnostic: $expected_message" >&2
        exit 1
    fi
}

expect_failure "local CA is missing" \
    "$preparer" --ca "$test_root/missing.crt" --output "$test_root/missing.der"

printf 'not a certificate\n' > "$test_root/invalid.crt"
expect_failure "not a valid X.509 certificate" \
    "$preparer" --ca "$test_root/invalid.crt" --output "$test_root/invalid.der"

openssl req -new -newkey rsa:2048 -nodes \
    -subj '/CN=SentriCam Local CA Preparation Test' \
    -keyout "$test_root/test.key" \
    -out "$test_root/test.csr" >/dev/null 2>&1
printf 'basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n' > "$test_root/ca.ext"
openssl x509 -req -in "$test_root/test.csr" \
    -signkey "$test_root/test.key" \
    -days 1 \
    -extfile "$test_root/ca.ext" \
    -out "$test_root/test.crt" >/dev/null 2>&1

cp "$test_root/test.crt" "$test_root/certificate-and-key.pem"
cat "$test_root/test.key" >> "$test_root/certificate-and-key.pem"
expect_failure "contains private key material" \
    "$preparer" --ca "$test_root/certificate-and-key.pem" --output "$test_root/private.der"
test ! -e "$test_root/private.der"

"$preparer" --ca "$test_root/test.crt" --output "$test_root/generated.der" >/dev/null
openssl x509 -inform DER -in "$test_root/generated.der" -noout >/dev/null
source_fingerprint=$(openssl x509 -in "$test_root/test.crt" -noout -fingerprint -sha256)
generated_fingerprint=$(openssl x509 -inform DER -in "$test_root/generated.der" -noout -fingerprint -sha256)
test "$source_fingerprint" = "$generated_fingerprint"
if LC_ALL=C grep -aq 'PRIVATE KEY' "$test_root/generated.der"; then
    echo "Generated certificate resource contains private key text." >&2
    exit 1
fi

printf 'SentriCam local CA preparation checks passed.\n'
