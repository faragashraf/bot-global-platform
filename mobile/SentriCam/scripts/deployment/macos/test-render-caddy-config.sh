#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
renderer="$script_dir/render-caddy-config.sh"
template="$script_dir/Caddyfile.template"
test_root=$(mktemp -d /tmp/sentricam-caddy-render-test.XXXXXX)
cleanup() {
    case "$test_root" in
        /tmp/sentricam-caddy-render-test.*) rm -rf "$test_root" ;;
    esac
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$test_root/runtime/app/web" "$test_root/runtime/config" "$test_root/runtime/logs"
cat > "$test_root/caddy" <<'EOF'
#!/bin/sh
[ "$1" = "validate" ] || exit 64
exit 0
EOF
chmod 0755 "$test_root/caddy"

render() {
    case_name=$1
    advertised_url=$2
    bonjour_hostname=${3-}
    output="$test_root/runtime/config/$case_name.Caddyfile"
    "$renderer" \
        --advertised-url "$advertised_url" \
        --bonjour-hostname "$bonjour_hostname" \
        --app-root "$test_root/runtime" \
        --template "$template" \
        --output "$output" \
        --caddy-bin "$test_root/caddy" >/dev/null
    sed -n '1p' "$output"
}

dual=$(render dual https://192.0.2.44 ' Example-Mac ')
[ "$dual" = 'https://192.0.2.44, https://example-mac.local {' ]

lan_only=$(render lan-only https://192.0.2.44 '')
[ "$lan_only" = 'https://192.0.2.44 {' ]

normalized=$(render normalized https://192.0.2.44 ' Example-Mac.LOCAL ')
[ "$normalized" = 'https://192.0.2.44, https://example-mac.local {' ]

deduplicated=$(render deduplicated https://example-mac.local 'Example-Mac')
[ "$deduplicated" = 'https://example-mac.local {' ]

invalid=$(render invalid https://192.0.2.44 'invalid host!')
[ "$invalid" = 'https://192.0.2.44 {' ]

grep -Fq '/usr/sbin/scutil --get LocalHostName' "$script_dir/run-caddy.sh"
if grep -REn '192\.168\.[0-9]{1,3}\.[0-9]{1,3}|/Users/' \
    "$script_dir/run-caddy.sh" \
    "$script_dir/render-caddy-config.sh" \
    "$script_dir/Caddyfile.template"; then
    echo "Deployment implementation contains a machine-specific literal." >&2
    exit 1
fi

printf 'SentriCam Caddy identity rendering checks passed.\n'
