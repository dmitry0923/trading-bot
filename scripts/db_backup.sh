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

DB_NAME="${DB_NAME:-trading_bot}"
DB_USER="${DB_USER:-trader}"

# На чистой VM/остановленной БД деплой не должен падать: бэкапить нечего.
if ! docker compose ps --status running --services 2>/dev/null | grep -qx "$SERVICE"; then
  echo "[db_backup] $SERVICE is not running - skipping pre-migration backup (fresh deploy)"
  exit 0
fi

stamp="$(date -u +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/trading_bot_${stamp}.dump"

echo "[db_backup] dumping -> $out"
# Имя БД/роль берём из env самого контейнера: значения из .env могут содержать кавычки
# или CRLF и ломать pg_dump -d (скан-10 P2).
docker compose exec -T "$SERVICE" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$out"

size=$(wc -c < "$out" | tr -d ' ')
if [ "$size" -lt 1024 ]; then
  echo "[db_backup] ERROR: dump looks empty ($size bytes) - aborting deploy" >&2
  exit 1
fi

# Проверяем, что это действительно архив pg_dump (а не текст ошибки).
if ! docker compose exec -T "$SERVICE" pg_restore -l > /dev/null 2>&1 < "$out"; then
  echo "[db_backup] WARN: pg_restore -l could not read the dump (size=$size) - continuing" >&2
fi

echo "[db_backup] OK: $out ($size bytes)"
find "$BACKUP_DIR" -name 'trading_bot_*.dump' -type f -mtime +"$RETENTION_DAYS" -delete || true
