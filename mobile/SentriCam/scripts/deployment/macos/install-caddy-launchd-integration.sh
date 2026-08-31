#!/bin/sh
set -eu

usage() {
    echo "Usage: $0 --app-root PATH [--activate]" >&2
    exit 64
}

app_root=""
activate=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --app-root) app_root=${2-}; shift 2 ;;
        --activate) activate=1; shift ;;
        *) usage ;;
    esac
done
[ -n "$app_root" ] || usage

source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
app_root=$(CDPATH= cd -- "$app_root" && pwd -P)
user_home=${HOME:?HOME must identify the launchd user home}
launch_agent="$user_home/Library/LaunchAgents/com.sentricam.caddy.plist"
launch_domain="gui/$(id -u)"
runtime_runner="$app_root/scripts/run-caddy.sh"
runtime_renderer="$app_root/scripts/render-caddy-config.sh"
runtime_template="$app_root/config/Caddyfile.template"

mkdir -p "$app_root/scripts" "$app_root/config" "$app_root/logs" "$(dirname "$launch_agent")"
install -m 0755 "$source_dir/run-caddy.sh" "$runtime_runner"
install -m 0755 "$source_dir/render-caddy-config.sh" "$runtime_renderer"
install -m 0644 "$source_dir/Caddyfile.template" "$runtime_template"

"$runtime_runner" --render-only

escape_sed_replacement() {
    printf '%s' "$1" | sed 's/[\\&|]/\\&/g'
}

runner_value=$(escape_sed_replacement "$runtime_runner")
root_value=$(escape_sed_replacement "$app_root")
home_value=$(escape_sed_replacement "$user_home")
stdout_value=$(escape_sed_replacement "$app_root/logs/caddy-launchd.out.log")
stderr_value=$(escape_sed_replacement "$app_root/logs/caddy-launchd.err.log")
temporary_plist=$(mktemp "${launch_agent}.tmp.XXXXXX")
cleanup() {
    rm -f "$temporary_plist"
}
trap cleanup EXIT HUP INT TERM

sed \
    -e "s|__SENTRICAM_CADDY_RUNNER__|$runner_value|g" \
    -e "s|__SENTRICAM_APP_ROOT__|$root_value|g" \
    -e "s|__SENTRICAM_HOME__|$home_value|g" \
    -e "s|__SENTRICAM_CADDY_STDOUT__|$stdout_value|g" \
    -e "s|__SENTRICAM_CADDY_STDERR__|$stderr_value|g" \
    "$source_dir/com.sentricam.caddy.plist.template" > "$temporary_plist"
plutil -lint "$temporary_plist"

if [ -f "$launch_agent" ] && ! cmp -s "$temporary_plist" "$launch_agent"; then
    timestamp=$(date -u '+%Y%m%dT%H%M%SZ')
    cp -p "$launch_agent" "${launch_agent}.bak.${timestamp}"
fi
chmod 0644 "$temporary_plist"
mv -f "$temporary_plist" "$launch_agent"
trap - EXIT HUP INT TERM

if [ "$activate" -eq 1 ]; then
    if launchctl print "$launch_domain/com.sentricam.caddy" >/dev/null 2>&1; then
        launchctl bootout "$launch_domain/com.sentricam.caddy"
    fi
    bootstrap_attempt=1
    while ! launchctl bootstrap "$launch_domain" "$launch_agent"; do
        if [ "$bootstrap_attempt" -ge 5 ]; then
            echo "Unable to bootstrap com.sentricam.caddy after $bootstrap_attempt attempts." >&2
            exit 70
        fi
        bootstrap_attempt=$((bootstrap_attempt + 1))
        sleep 1
    done
fi
