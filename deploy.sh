#!/usr/bin/env bash
# ============================================================
# test-agent 一键部署（阿里云 ECS / 任意 Linux）
#
# 首次部署（服务器上无需提前 clone）：
#   curl -fsSL https://raw.githubusercontent.com/candy-wind/test-agent/master/deploy.sh | bash
#   或把本文件传到服务器后执行：bash deploy.sh
#
# 已部署后更新（拉取最新代码并重建）：
#   cd ~/test-agent && ./deploy.sh
#
# 可选环境变量：
#   DEPLOY_DIR=/opt/test-agent   代码目录，默认 ~/test-agent
#   GIT_REPO=<仓库地址>          默认 GitHub 官方仓库，访问不了时可换成镜像
#   GIT_BRANCH=master            分支
#   APP_IMAGE=<镜像地址>         使用 ACR 预构建镜像；留空则本地构建
# ============================================================
set -euo pipefail

REPO_URL="${GIT_REPO:-https://github.com/candy-wind/test-agent.git}"
BRANCH="${GIT_BRANCH:-master}"
DEPLOY_DIR="${DEPLOY_DIR:-$HOME/test-agent}"

COMPOSE_FILE="docker-compose.prod.yml"
APP_UID=10001
LOG_ROOT="/mnt/logs"
DATA_ROOT="/mnt/test-agent"

log()  { printf '[deploy] %s\n' "$*"; }
warn() { printf '[deploy][WARN] %s\n' "$*"; }
err()  { printf '[deploy][ERROR] %s\n' "$*" >&2; exit 1; }

# ---------- 0. 同步代码 ----------
if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  SCRIPT_IS_FILE=1
else
  # curl | bash 或 bash <(...) 时没有脚本路径，使用当前目录
  SCRIPT_DIR="$PWD"
  SCRIPT_IS_FILE=0
fi

if [ "$SCRIPT_IS_FILE" = "1" ] && [ -d "$SCRIPT_DIR/.git" ]; then
  CODE_DIR="$SCRIPT_DIR"
else
  CODE_DIR="$DEPLOY_DIR"
fi

if [ "${SKIP_SYNC:-0}" != "1" ]; then
  command -v git >/dev/null 2>&1 \
    || err "未安装 git，请先安装：sudo dnf install -y git 或 sudo apt-get install -y git"

  if [ -d "$CODE_DIR/.git" ]; then
    log "拉取最新代码：$CODE_DIR（$BRANCH）"
    if ! git -C "$CODE_DIR" diff --quiet || ! git -C "$CODE_DIR" diff --cached --quiet; then
      err "代码目录存在未提交的改动，已停止更新。请先处理：cd $CODE_DIR && git status"
    fi
    git -C "$CODE_DIR" fetch --quiet origin "$BRANCH" \
      || err "拉取代码失败：请检查服务器到 GitHub 的网络，或改用 GIT_REPO=<镜像地址> bash deploy.sh"
    git -C "$CODE_DIR" checkout --quiet -B "$BRANCH" FETCH_HEAD
    git -C "$CODE_DIR" reset --hard --quiet FETCH_HEAD
    log "当前版本：$(git -C "$CODE_DIR" log -1 --format='%h %s')"
  else
    if [ -e "$CODE_DIR" ] && [ -n "$(ls -A "$CODE_DIR" 2>/dev/null || true)" ]; then
      err "目录 $CODE_DIR 已存在且不是 git 仓库，请清理后重试，或指定 DEPLOY_DIR=<其他目录>"
    fi
    log "克隆代码到 $CODE_DIR"
    mkdir -p "$(dirname "$CODE_DIR")"
    git clone --quiet --depth=1 --branch "$BRANCH" "$REPO_URL" "$CODE_DIR" \
      || err "克隆失败：请检查网络，或使用 GIT_REPO=<镜像地址> bash deploy.sh"
  fi

  [ -f "$CODE_DIR/deploy.sh" ] || err "仓库中缺少 deploy.sh"
  # 复制到临时文件再执行：当前脚本可能刚被 reset 更新，避免边读边改导致执行错乱
  run_script="$(mktemp /tmp/test-agent-deploy.XXXXXX.sh)"
  cp "$CODE_DIR/deploy.sh" "$run_script"
  exec env SKIP_SYNC=1 DEPLOY_DIR="$CODE_DIR" bash "$run_script"
fi

cd "$CODE_DIR"

# ---------- 1. 环境检查 ----------
command -v docker >/dev/null 2>&1 \
  || err "未安装 Docker，请参考 docs/deploy-aliyun.md 第 3 节"
docker compose version >/dev/null 2>&1 \
  || err "缺少 docker compose 插件，请参考 docs/deploy-aliyun.md 第 3 节"

DOCKER=(docker)
if ! docker info >/dev/null 2>&1; then
  if sudo -n docker info >/dev/null 2>&1; then
    DOCKER=(sudo docker)
    warn "当前用户没有 docker 权限，本次自动使用 sudo；建议执行 sudo usermod -aG docker \"$USER\" 后重新登录"
  else
    err "Docker 未运行或无权限：执行 sudo systemctl start docker，或把当前用户加入 docker 组后重新登录"
  fi
fi

[ -f "$COMPOSE_FILE" ] || err "缺少 $COMPOSE_FILE"

docker_info="$("${DOCKER[@]}" info 2>/dev/null || true)"
case "$docker_info" in
  *"Registry Mirrors"*) ;;
  *) warn "Docker 未配置镜像加速，拉取基础镜像可能很慢，参考 docs/deploy-aliyun.md 第 3 节" ;;
esac

# ---------- 2. 准备 .env ----------
if [ ! -f .env ]; then
  cp .env.example .env
  log "已从 .env.example 创建 .env"
fi

get_env() { sed -n "s/^$1=//p" .env | tail -n 1; }

set_env() {
  local key="$1" value="$2"
  awk -v k="$key" -v v="$value" '
    $0 ~ "^" k "=" { print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }
  ' .env > .env.tmp
  mv .env.tmp .env
}

generated_db_password=0
db_password="$(get_env DB_PASSWORD)"
if [ -z "$db_password" ] || [ "$db_password" = "换成强密码" ]; then
  db_password="$(openssl rand -hex 16 2>/dev/null || head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  [ -n "$db_password" ] || err "无法生成数据库密码（缺少 openssl 或 /dev/urandom）"
  set_env DB_PASSWORD "$db_password"
  generated_db_password=1
  log "已生成随机数据库密码并写入 .env"
fi

if [ -z "$(get_env APP_SECRET_KEY)" ]; then
  app_secret="$(openssl rand -base64 32 2>/dev/null | tr -d '\n' || head -c 32 /dev/urandom | base64 | tr -d '\n')"
  [ -n "$app_secret" ] || err "无法生成 APP_SECRET_KEY（缺少 openssl 或 /dev/urandom）"
  set_env APP_SECRET_KEY "$app_secret"
  log "已生成 APP_SECRET_KEY 并写入 .env（请备份，更换后已保存的 API Key 将无法解密）"
fi

[ -n "$(get_env LOG_SERVICE_NAME)" ] || set_env LOG_SERVICE_NAME "test-agent"
chmod 600 .env

LOG_SERVICE_NAME="$(get_env LOG_SERVICE_NAME)"
LOG_SERVICE_NAME="${LOG_SERVICE_NAME:-test-agent}"
APP_PORT="$(get_env APP_PORT)"
APP_PORT="${APP_PORT:-8080}"
APP_IMAGE="${APP_IMAGE:-$(get_env APP_IMAGE)}"

# 新建 .env 但已有数据卷：数据库密码大概率对不上，提前提醒
if [ "$generated_db_password" = "1" ] \
  && "${DOCKER[@]}" volume inspect test-agent-prod_pgdata >/dev/null 2>&1; then
  warn "检测到已有数据库数据卷，但 .env 是新建的：密码可能和旧数据不一致，应用会连不上数据库"
  warn "请找回旧的 .env；确实要重来可执行 ${DOCKER[*]} volume rm test-agent-prod_pgdata（会清空数据库）"
fi

# ---------- 3. 准备数据目录 ----------
log "准备数据目录：${LOG_ROOT}/${LOG_SERVICE_NAME}、${DATA_ROOT}"
if [ "$(id -u)" = "0" ]; then
  mkdir -p "${LOG_ROOT}/${LOG_SERVICE_NAME}" "${DATA_ROOT}/kb" "${DATA_ROOT}/uploads"
  chown -R "${APP_UID}:${APP_UID}" "${LOG_ROOT}/${LOG_SERVICE_NAME}" "${DATA_ROOT}"
else
  sudo mkdir -p "${LOG_ROOT}/${LOG_SERVICE_NAME}" "${DATA_ROOT}/kb" "${DATA_ROOT}/uploads"
  sudo chown -R "${APP_UID}:${APP_UID}" "${LOG_ROOT}/${LOG_SERVICE_NAME}" "${DATA_ROOT}"
fi

if command -v mountpoint >/dev/null 2>&1 && ! mountpoint -q /mnt; then
  warn "/mnt 不是独立挂载点，日志和知识库文件会占用系统盘"
fi

# ---------- 4. 构建并启动 ----------
if [ -n "$APP_IMAGE" ]; then
  log "使用预构建镜像：${APP_IMAGE}"
  "${DOCKER[@]}" compose -f "$COMPOSE_FILE" pull app
  "${DOCKER[@]}" compose -f "$COMPOSE_FILE" up -d --no-build --remove-orphans
else
  log "本地构建并启动（首次构建需要下载 Maven 依赖，通常 3-10 分钟）"
  "${DOCKER[@]}" compose -f "$COMPOSE_FILE" up -d --build --remove-orphans
fi

# ---------- 5. 等待健康检查 ----------
log "等待应用健康检查通过..."
status="unknown"
for _ in $(seq 1 90); do
  status="$("${DOCKER[@]}" inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' test-agent-app 2>/dev/null || true)"
  [ "$status" = "healthy" ] && break
  sleep 5
done

if [ "$status" != "healthy" ]; then
  warn "健康检查未通过（当前：${status}），最近日志："
  "${DOCKER[@]}" compose -f "$COMPOSE_FILE" logs --tail=80 app || true
  err "部署未完成，请根据上面的日志排查（常见：数据库密码不一致、端口被占用、内存不足）"
fi

# 清理构建产生的悬空镜像，避免系统盘被旧层占满
"${DOCKER[@]}" image prune -f >/dev/null 2>&1 || true

log "部署完成"
printf '  访问地址：http://<服务器公网IP>:%s\n' "$APP_PORT"
printf '  代码目录：%s\n' "$CODE_DIR"
printf '  配置文件：%s/.env（请备份，内含数据库密码与 APP_SECRET_KEY）\n' "$CODE_DIR"
printf '  日志文件：%s/%s/app.log\n' "$LOG_ROOT" "$LOG_SERVICE_NAME"
printf '  更新代码：cd %s && ./deploy.sh\n' "$CODE_DIR"
printf '  查看日志：docker compose -f %s logs -f app\n' "$COMPOSE_FILE"
