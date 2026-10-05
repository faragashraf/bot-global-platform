#!/bin/sh
set -eu

json_escape() {
  sed 's/\\/\\\\/g; s/"/\\"/g'
}

write_status() {
  now="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  uptime_seconds="$(awk '{ printf "%d", $1 }' /host/proc/uptime)"
  load1="$(awk '{ print $1 }' /host/proc/loadavg)"
  load5="$(awk '{ print $2 }' /host/proc/loadavg)"
  load15="$(awk '{ print $3 }' /host/proc/loadavg)"

  mem_total_kb="$(awk '/^MemTotal:/ { print $2 }' /host/proc/meminfo)"
  mem_available_kb="$(awk '/^MemAvailable:/ { print $2 }' /host/proc/meminfo)"
  swap_total_kb="$(awk '/^SwapTotal:/ { print $2 }' /host/proc/meminfo)"
  swap_free_kb="$(awk '/^SwapFree:/ { print $2 }' /host/proc/meminfo)"
  mem_used_kb="$((mem_total_kb - mem_available_kb))"
  swap_used_kb="$((swap_total_kb - swap_free_kb))"

  disk_line="$(df -Pk /host/root | awk 'NR == 2 { print $2 "|" $3 "|" $4 "|" $5 }')"
  disk_total_kb="$(printf "%s" "$disk_line" | cut -d'|' -f1)"
  disk_used_kb="$(printf "%s" "$disk_line" | cut -d'|' -f2)"
  disk_available_kb="$(printf "%s" "$disk_line" | cut -d'|' -f3)"
  disk_used_percent="$(printf "%s" "$disk_line" | cut -d'|' -f4 | tr -d '%')"

  containers_json="$(docker ps --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}' \
    | awk -F'|' '
      function esc(value) {
        gsub(/\\/, "\\\\", value);
        gsub(/"/, "\\\"", value);
        return value;
      }
      BEGIN { printf "[" }
      {
        if (NR > 1) printf ",";
        printf "{\"name\":\"%s\",\"image\":\"%s\",\"status\":\"%s\",\"ports\":\"%s\"}", esc($1), esc($2), esc($3), esc($4)
      }
      END { printf "]" }')"

  stats_json="$(docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}' \
    | awk -F'|' '
      function esc(value) {
        gsub(/\\/, "\\\\", value);
        gsub(/"/, "\\\"", value);
        return value;
      }
      BEGIN { printf "[" }
      {
        if (NR > 1) printf ",";
        printf "{\"name\":\"%s\",\"cpu\":\"%s\",\"memory\":\"%s\",\"memoryPercent\":\"%s\",\"network\":\"%s\",\"block\":\"%s\"}", esc($1), esc($2), esc($3), esc($4), esc($5), esc($6)
      }
      END { printf "]" }')"

  host_name="$(printf "%s" "${SERVER_NAME:-$(cat /host/proc/sys/kernel/hostname)}" | json_escape)"
  kernel="$(cat /host/proc/sys/kernel/osrelease | json_escape)"

  cat > /out/status.json.tmp <<JSON
{
  "generatedAtUtc": "$now",
  "host": {
    "name": "$host_name",
    "kernel": "$kernel",
    "uptimeSeconds": $uptime_seconds,
    "load": {
      "one": "$load1",
      "five": "$load5",
      "fifteen": "$load15"
    }
  },
  "memory": {
    "totalKiB": $mem_total_kb,
    "usedKiB": $mem_used_kb,
    "availableKiB": $mem_available_kb,
    "swapTotalKiB": $swap_total_kb,
    "swapUsedKiB": $swap_used_kb,
    "swapFreeKiB": $swap_free_kb
  },
  "disk": {
    "mount": "/",
    "totalKiB": $disk_total_kb,
    "usedKiB": $disk_used_kb,
    "availableKiB": $disk_available_kb,
    "usedPercent": $disk_used_percent
  },
  "docker": {
    "containers": $containers_json,
    "stats": $stats_json
  }
}
JSON
  mv /out/status.json.tmp /out/status.json
}

while true; do
  write_status || true
  sleep 5
done
