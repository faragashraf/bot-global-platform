#!/bin/sh
set -eu

usage() {
    echo "Usage: $0 --advertised-url HTTPS_ORIGIN [--bonjour-hostname NAME] --app-root PATH --template PATH --output PATH --caddy-bin PATH" >&2
    exit 64
}

advertised_url=""
bonjour_hostname=""
app_root=""
template_path=""
output_path=""
caddy_bin=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --advertised-url) advertised_url=${2-}; shift 2 ;;
        --bonjour-hostname) bonjour_hostname=${2-}; shift 2 ;;
        --app-root) app_root=${2-}; shift 2 ;;
        --template) template_path=${2-}; shift 2 ;;
        --output) output_path=${2-}; shift 2 ;;
        --caddy-bin) caddy_bin=${2-}; shift 2 ;;
        *) usage ;;
    esac
done

[ -n "$advertised_url" ] || usage
[ -n "$app_root" ] || usage
[ -f "$template_path" ] || usage
[ -n "$output_path" ] || usage
[ -x "$caddy_bin" ] || usage

case "$advertised_url" in
    https://*) ;;
    *) echo "Refusing to render a non-HTTPS advertised Hub URL." >&2; exit 65 ;;
esac

site_remainder=${advertised_url#https://}
case "$site_remainder" in
    ""|*/*|*\?*|*\#*|*@*)
        echo "Refusing to render an advertised Hub URL that is not an HTTPS origin." >&2
        exit 65
        ;;
esac
if printf '%s' "$advertised_url" | LC_ALL=C grep -q '[[:space:]]'; then
    echo "Refusing to render an advertised Hub URL containing whitespace." >&2
    exit 65
fi

normalize_bonjour_hostname() {
    candidate=$(printf '%s' "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    [ -n "$candidate" ] || return 1
    case "$candidate" in
        *[!A-Za-z0-9.-]*) return 1 ;;
    esac

    candidate=$(printf '%s' "$candidate" | tr '[:upper:]' '[:lower:]')
    case "$candidate" in
        *.local) ;;
        *) candidate="$candidate.local" ;;
    esac

    printf '%s\n' "$candidate" | awk -F. '
        length($0) > 253 || NF < 2 || $NF != "local" { exit 1 }
        {
            for (field_index = 1; field_index <= NF; field_index++) {
                if (length($field_index) < 1 || length($field_index) > 63) exit 1
                if ($field_index !~ /^[a-z0-9]$/ && $field_index !~ /^[a-z0-9][a-z0-9-]*[a-z0-9]$/) exit 1
            }
        }
    ' || return 1
    printf '%s\n' "$candidate"
}

site_addresses="$advertised_url"
normalized_bonjour_hostname=$(normalize_bonjour_hostname "$bonjour_hostname" || true)
if [ -n "$normalized_bonjour_hostname" ]; then
    bonjour_url="https://$normalized_bonjour_hostname"
    if ! printf '%s\n' "$advertised_url" | grep -Fqix -- "$bonjour_url"; then
        site_addresses="$site_addresses, $bonjour_url"
    fi
fi

web_root="$app_root/app/web"
access_log="$app_root/logs/caddy-access.log"
[ -d "$web_root" ] || {
    echo "React web root does not exist: $web_root" >&2
    exit 66
}
mkdir -p "$(dirname "$output_path")" "$app_root/logs"

escape_sed_replacement() {
    printf '%s' "$1" | sed 's/[\\&|]/\\&/g'
}

site_value=$(escape_sed_replacement "$site_addresses")
web_value=$(escape_sed_replacement "$web_root")
log_value=$(escape_sed_replacement "$access_log")
temporary_path=$(mktemp "${output_path}.tmp.XXXXXX")
cleanup() {
    rm -f "$temporary_path"
}
trap cleanup EXIT HUP INT TERM

sed \
    -e "s|__SENTRICAM_SITE_ADDRESSES__|$site_value|g" \
    -e "s|__SENTRICAM_WEB_ROOT__|$web_value|g" \
    -e "s|__SENTRICAM_ACCESS_LOG__|$log_value|g" \
    "$template_path" > "$temporary_path"

if grep -q '__SENTRICAM_' "$temporary_path"; then
    echo "Rendered Caddy configuration still contains template placeholders." >&2
    exit 65
fi

"$caddy_bin" validate --config "$temporary_path" --adapter caddyfile

if [ -f "$output_path" ] && cmp -s "$temporary_path" "$output_path"; then
    exit 0
fi

if [ -f "$output_path" ]; then
    timestamp=$(date -u '+%Y%m%dT%H%M%SZ')
    cp -p "$output_path" "${output_path}.bak.${timestamp}"
fi
chmod 0644 "$temporary_path"
mv -f "$temporary_path" "$output_path"
trap - EXIT HUP INT TERM
printf '%s\n' "$site_addresses"
