#!/usr/bin/env bash
# 阿里云 ECS 一键部署：构建镜像、准备数据目录、启动容器
# 用法：./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE_FILE="docker-compose.prod.yml"
APP_UID=10001
LOG_ROOT="/mnt/logs"
DATA_ROOT="/mnt/test-agent"

log() { printf '[deploy] %s\n' "$*"; }
err() { printf '[deploy][ERROR] %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || err "未安装 Docker，参考 docs/deploy-aliyun.md 第 3 节"
docker compose version >/dev/null 2>&1 || err "缺少 docker compose 插件，参考 docs/deploy-aliyun.md 第 3 节"

# 当前用户没有 docker 权限时退回 sudo
DOCKER="docker"
if ! docker info >/dev/null 2>&1; then
  if sudo -n docker info >/dev/null 2>&1; then
    DOCKER="sudo docker"
  else
    err "Docker 守护进程未运行或无权限：执行 sudo systemctl start docker，或把当前用户加入 docker 组后重新登录"
  fi
fi

if [ ! -f .env ]; then
  cp .env.example .env
  secret="$(openssl rand -base64 32 2>/dev/null || head -c 32 /dev/urandom | base64)"
  sed -i "s|^APP_SECRET_KEY=.*|APP_SECRET_KEY=${secret}|" .env
  log "已创建 .env，并写入随机 APP_SECRET_KEY"
  err "请先修改 .env 中的 DB_PASSWORD（换成强密码），然后重新执行 ./deploy.sh"
fi

# 只读取需要的键，不执行 .env 内容
read_env() { grep -E "^$1=" .env | tail -n 1 | cut -d= -f2-; }

DB_PASSWORD="$(read_env DB_PASSWORD)"
APP_SECRET_KEY="$(read_env APP_SECRET_KEY)"
LOG_SERVICE_NAME="${LOG_SERVICE_NAME:-$(read_env LOG_SERVICE_NAME)}"
LOG_SERVICE_NAME="${LOG_SERVICE_NAME:-test-agent}"
APP_PORT="${APP_PORT:-$(read_env APP_PORT)}"
APP_PORT="${APP_PORT:-8080}"

if [ -z "$DB_PASSWORD" ] || [ "$DB_PASSWORD" = "换成强密码" ]; then
  err ".env 中 DB_PASSWORD 未设置，请改成强密码后重试"
fi
[ -n "$APP_SECRET_KEY" ] || err ".env 中 APP_SECRET_KEY 为空"

log "准备数据目录：${LOG_ROOT}/${LOG_SERVICE_NAME}、${DATA_ROOT}"
sudo mkdir -p "${LOG_ROOT}/${LOG_SERVICE_NAME}" "${DATA_ROOT}/kb" "${DATA_ROOT}/uploads"
sudo chown -R "${APP_UID}:${APP_UID}" "${LOG_ROOT}/${LOG_SERVICE_NAME}" "${DATA_ROOT}"

log "构建并启动容器（首次构建需要下载依赖，耗时较长）"
$DOCKER compose -f "$COMPOSE_FILE" up -d --build

log "等待应用健康检查通过..."
status="unknown"
for _ in $(seq 1 60); do
  status="$($DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' test-agent-app 2>/dev/null || true)"
  if [ "$status" = "healthy" ]; then
    break
  fi
  sleep 5
done

if [ "$status" = "healthy" ]; then
  log "应用已就绪"
else
  log "健康检查尚未通过（当前：${status}），可执行以下命令查看原因："
  log "  $DOCKER compose -f $COMPOSE_FILE logs -f app"
fi

log "访问地址： http://<服务器公网IP>:${APP_PORT:-8080}"
log "日志文件： ${LOG_ROOT}/${LOG_SERVICE_NAME}/app.log"
log "停止服务： $DOCKER compose -f $COMPOSE_FILE down        （保留数据）"
log "查看日志： $DOCKER compose -f $COMPOSE_FILE logs -f app"
