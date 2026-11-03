#!/usr/bin/env bash
# Первичная подготовка VM в Yandex Cloud под SIM-деплой (docs/55).
#
# Запуск (Ubuntu 22.04/24.04, от root/sudo):
#   sudo bash vm_bootstrap.sh [--no-swap]
#
# Идемпотентен: повторный запуск не ломает установленный Docker и не
# пересоздаёт swap. Скрипт НЕ трогает firewall и НЕ создаёт секреты.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "run as root: sudo bash $0" >&2
  exit 1
fi

WITH_SWAP=true
for arg in "$@"; do
  case "$arg" in
    --no-swap) WITH_SWAP=false ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

TARGET_USER="${SUDO_USER:-ubuntu}"
APP_DIR="/opt/trading-bot"
SWAP_FILE="/swapfile"
SWAP_SIZE_MB="${SWAP_SIZE_MB:-2048}"

echo "[bootstrap] user=$TARGET_USER app_dir=$APP_DIR swap=$WITH_SWAP"

# --- timezone / базовые пакеты ---
timedatectl set-timezone Europe/Moscow || true
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl gnupg lsb-release jq

# --- Docker Engine + compose plugin (официальный репозиторий) ---
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  # shellcheck source=/dev/null
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
else
  echo "[bootstrap] docker already installed: $(docker --version)"
fi

# compose plugin обязателен (!reset в compose-файлах требует >= 2.24).
if ! docker compose version >/dev/null 2>&1; then
  echo "[bootstrap] ERROR: docker compose plugin is missing" >&2
  exit 1
fi
compose_version="$(docker compose version --short 2>/dev/null | sed 's/^v//')"
compose_major="${compose_version%%.*}"
compose_rest="${compose_version#*.}"
compose_minor="${compose_rest%%.*}"
if [ -z "$compose_version" ] || [ "$compose_major" -lt 2 ] ||
  { [ "$compose_major" -eq 2 ] && [ "$compose_minor" -lt 24 ]; }; then
  echo "[bootstrap] ERROR: docker compose $compose_version < 2.24 (needs !reset); update docker-compose-plugin" >&2
  exit 1
fi
echo "[bootstrap] docker compose $compose_version OK"

# Пользователь CI (VM_USER) должен управлять docker без sudo.
if id "$TARGET_USER" >/dev/null 2>&1; then
  usermod -aG docker "$TARGET_USER"
fi

# --- ротация логов docker (compose-лимиты уже есть, это страховка) ---
mkdir -p /etc/docker
if [ ! -f /etc/docker/daemon.json ]; then
  cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "50m", "max-file": "3" }
}
EOF
  systemctl restart docker
fi

# --- swap (4 GB VM: страховка от OOM при backtest/деплое) ---
if $WITH_SWAP && [ ! -f "$SWAP_FILE" ]; then
  if ! swapon --show | grep -q .; then
    fallocate -l "${SWAP_SIZE_MB}M" "$SWAP_FILE" || dd if=/dev/zero of="$SWAP_FILE" bs=1M count="$SWAP_SIZE_MB"
    chmod 600 "$SWAP_FILE"
    mkswap "$SWAP_FILE"
    swapon "$SWAP_FILE"
    grep -q "^${SWAP_FILE} " /etc/fstab || echo "${SWAP_FILE} none swap sw 0 0" >> /etc/fstab
    echo "[bootstrap] swap ${SWAP_SIZE_MB}MB enabled"
  else
    echo "[bootstrap] swap already active - skipping"
  fi
fi

# --- каталог деплоя (scp-action пишет сюда под VM_USER) ---
mkdir -p "$APP_DIR/backups"
if id "$TARGET_USER" >/dev/null 2>&1; then
  chown -R "$TARGET_USER":"$TARGET_USER" "$APP_DIR"
fi

cat <<EOF

[bootstrap] done.

Дальше (docs/55):
  1. Создайте $APP_DIR/.env с DB_PASS, JWT_SECRET, AUTH_*, ANALYTICS_*,
     METRICS_SCRAPE_TOKEN, GRAFANA_ADMIN_PASSWORD и (для Managed) DB_HOST/REDIS_HOST.
  2. Права: chmod 600 $APP_DIR/.env
  3. Проверьте: docker compose version, docker run --rm hello-world
  4. Запустите GitHub Actions deploy-sim (push в main).

EOF
