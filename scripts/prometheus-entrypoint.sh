#!/bin/sh
set -eu

: "${AUTH_USER:?AUTH_USER must be set}"
: "${AUTH_PASSWORD:?AUTH_PASSWORD must be set}"

case "$AUTH_USER" in
  *[!A-Za-z0-9._-]*)
    echo "AUTH_USER may contain only letters, digits, dot, underscore and hyphen" >&2
    exit 1
    ;;
esac

sed "s|__AUTH_USER__|${AUTH_USER}|g" \
  /etc/prometheus/prometheus.yml.template > /tmp/trading-bot-prometheus.yml

# A file avoids YAML escaping bugs and keeps the password out of process args.
printf '%s' "$AUTH_PASSWORD" > /tmp/trading-bot-prometheus-password

exec /bin/prometheus \
  --config.file=/tmp/trading-bot-prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --web.console.libraries=/usr/share/prometheus/console_libraries \
  --web.console.templates=/usr/share/prometheus/consoles
