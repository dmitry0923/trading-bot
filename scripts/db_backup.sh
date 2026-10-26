#!/usr/bin/env bash
# Резервная копия БД ПЕРЕД авто-миграциями (Liquibase применяется при старте app).
#
# Зачем: миграции 037/042/043 меняют данные (DROP/дедуп/retention), а rollback в
# changeset'ах нет. Деплой без дампа оставлял единственную копию «в схеме впереди».
#
# Использование (на VM, из каталога с docker-compose.yml):
#   ./db_backup.sh                 # дамп в ./backups, retention 7 дней
#   BACKUP_DIR=/var/backups/tb ./db_backup.sh
#
# Восстановление (ручной шаг, НЕ автоматизирован намеренно):
#   docker compose stop app
#   docker compose exec -T postgres psql -U "$DB_USER" -d postgres -c 'DROP DATABASE ...'
#   docker compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --clean --if-exists < dump
#   docker compose start app
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-$(pwd)/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
SERVICE="${DB_SERVICE:-postgres}"

mkdir -p "$BACKUP_DIR"
umask 077

DB_NAME="${DB_NAME:-$(grep -E '^DB_NAME=' .env 2>/dev/null | cut -d= -f2- || true)}"
DB_USER="${DB_USER:-$(grep -E '^DB_USER=' .env 2>/dev/null | cut -d= -f2- || true)}"
DB_NAME="${DB_NAME:-trading_bot}"
DB_USER="${DB_USER:-trader}"

stamp="$(date -u +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/trading_bot_${stamp}.dump"

echo "[db_backup] dumping '$DB_NAME' (user=$DB_USER) -> $out"
# -Fc = custom format (сжатый, восстанавливается pg_restore); -T не берём: нужны все таблицы,
# включая hypertables TimescaleDB.
docker compose exec -T "$SERVICE" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$out"

size=$(wc -c < "$out" | tr -d ' ')
if [ "$size" -lt 1024 ]; then
  echo "[db_backup] ERROR: dump looks empty ($size bytes) - aborting deploy" >&2
  exit 1
fi

echo "[db_backup] OK: $out ($size bytes)"
find "$BACKUP_DIR" -name 'trading_bot_*.dump' -type f -mtime +"$RETENTION_DAYS" -delete || true
