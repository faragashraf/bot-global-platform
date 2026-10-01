#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
app_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
server_dir="$app_root/app/server"
template_path="$app_root/config/Caddyfile.template"
output_path="$app_root/config/Caddyfile"
renderer="$script_dir/render-caddy-config.sh"

find_executable() {
    for candidate in "$@"; do
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

dotnet_bin=${SENTRICAM_DOTNET_BIN:-}
if [ -z "$dotnet_bin" ]; then
    dotnet_bin=$(find_executable /usr/local/share/dotnet/dotnet /opt/homebrew/bin/dotnet /usr/local/bin/dotnet) || {
        echo "Unable to locate dotnet. Set SENTRICAM_DOTNET_BIN in the launchd environment." >&2
        exit 69
    }
fi

caddy_bin=${SENTRICAM_CADDY_BIN:-}
if [ -z "$caddy_bin" ]; then
    caddy_bin=$(find_executable /opt/homebrew/bin/caddy /usr/local/bin/caddy) || {
        echo "Unable to locate Caddy. Set SENTRICAM_CADDY_BIN in the launchd environment." >&2
        exit 69
    }
fi

[ -x "$dotnet_bin" ] || { echo "dotnet is not executable: $dotnet_bin" >&2; exit 69; }
[ -x "$caddy_bin" ] || { echo "Caddy is not executable: $caddy_bin" >&2; exit 69; }
[ -f "$server_dir/SentriCam.Api.dll" ] || { echo "Published Hub binary is missing." >&2; exit 66; }
[ -x "$renderer" ] || { echo "Caddy renderer is missing or not executable." >&2; exit 66; }

advertised_url=$(
    CDPATH= cd -- "$server_dir"
    "$dotnet_bin" SentriCam.Api.dll --print-advertised-hub-url
)

bonjour_hostname=""
if [ -x /usr/sbin/scutil ]; then
    bonjour_hostname=$(/usr/sbin/scutil --get LocalHostName 2>/dev/null || true)
fi

"$renderer" \
    --advertised-url "$advertised_url" \
    --bonjour-hostname "$bonjour_hostname" \
    --app-root "$app_root" \
    --template "$template_path" \
    --output "$output_path" \
    --caddy-bin "$caddy_bin"

if [ "${1-}" = "--render-only" ]; then
    exit 0
fi
[ "$#" -eq 0 ] || { echo "Usage: $0 [--render-only]" >&2; exit 64; }

exec "$caddy_bin" run --config "$output_path" --adapter caddyfile
