# 阿里云 ECS 容器化部署

面向 Linux 服务器：应用与 PostgreSQL 全部跑在 Docker 里，服务器上不需要装 JDK / Maven。
本地 Windows 的 `start.ps1`、`start.bat`、`stop.ps1` 不适用于 Linux。

---

## 1. 部署结构

| 组件 | 说明 |
| --- | --- |
| `test-agent-app` | 应用容器，镜像由仓库内 `Dockerfile` 构建（JDK 25 构建、JRE 25 运行） |
| `test-agent-pg` | PostgreSQL 16 + pgvector，不对宿主机暴露 5432，只走容器内网 |
| `pgdata` | 数据库命名卷，位于 `/var/lib/docker/volumes/`（系统盘） |
| `/mnt/logs/test-agent/` | 日志目录，按天滚动保留 15 天 |
| `/mnt/test-agent/kb/` | 知识库上传的原文件 |
| `/mnt/test-agent/uploads/` | 图片上传目录 |

对外只暴露应用端口（默认 8080），数据库不开放到公网。

---

## 2. 准备数据盘挂载到 /mnt

先确认 `/mnt` 是真的数据盘，而不是系统盘目录：

```bash
lsblk
df -h /mnt
```

如果数据盘（通常是 `/dev/vdb`）还没格式化挂载：

```bash
# 注意：mkfs 会清空磁盘，已有数据请勿执行
sudo mkfs.ext4 /dev/vdb
sudo mkdir -p /mnt
sudo blkid /dev/vdb          # 复制输出的 UUID
sudo cp /etc/fstab /etc/fstab.bak
echo 'UUID=替换成上一步的UUID /mnt ext4 defaults,nofail 0 0' | sudo tee -a /etc/fstab
sudo mount -a
df -h /mnt
```

---

## 3. 安装 Docker 与 Compose 插件

**Alibaba Cloud Linux 3 / CentOS 系：**

```bash
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

**Ubuntu 22.04 / 24.04：**

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2
```

启用并设置开机自启：

```bash
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"    # 重新登录后生效，之后不用每条命令都加 sudo
docker compose version
```

如果拉取 Docker Hub 镜像很慢或超时，在阿里云「容器镜像服务 → 镜像工具 → 镜像加速器」获取专属地址，写入 `/etc/docker/daemon.json` 后重启 Docker：

```json
{
  "registry-mirrors": ["https://<你的专属加速地址>.mirror.aliyuncs.com"]
}
```

```bash
sudo systemctl restart docker
```

> 专属地址需要登录阿里云控制台查看，每台账号不同。如果暂时没有，可先用 `https://docker.xuanyuan.me`、`https://docker.m.daocloud.io` 这类公共镜像源兜底（第三方服务，稳定性无 SLA）；旧的 `docker.mirrors.aliyuncs.com` 已停止服务，不要再配。

---

## 4. 一键部署（拉取代码 + 构建 + 启动）

服务器上不需要提前 clone，直接执行：

```bash
curl -fsSL https://raw.githubusercontent.com/candy-wind/test-agent/master/deploy.sh | bash
```

不放心管道执行的话，先下载再运行：

```bash
curl -fsSL -o deploy.sh https://raw.githubusercontent.com/candy-wind/test-agent/master/deploy.sh
less deploy.sh
bash deploy.sh
```

> 以上命令要求仓库是公开的。如果改成私有仓库，先在服务器配置 GitHub 凭据，或改用 `git clone` 后进入目录执行 `./deploy.sh`。

脚本会依次完成：

1. 把仓库克隆到 `~/test-agent`；已存在则 `git fetch` 并强制对齐到 `origin/master`
2. 检查 Docker / Compose 插件，当前用户无权限时自动改用 sudo
3. 首次运行自动生成 `.env`：随机数据库密码 + 随机 `APP_SECRET_KEY` + 随机管理员口令
4. 创建 `/mnt/logs/test-agent`、`/mnt/test-agent/{kb,uploads}` 并设置 uid 10001
5. 执行 `docker compose -f docker-compose.prod.yml up -d --build`
6. 轮询健康检查，失败时自动打印应用日志

可选环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DEPLOY_DIR` | `~/test-agent` | 代码目录 |
| `GIT_REPO` | GitHub 仓库 | GitHub 访问不了时换成镜像地址 |
| `GIT_BRANCH` | `master` | 分支 |
| `APP_IMAGE` | 空 | 填写 ACR 镜像地址则拉取镜像，不在服务器构建 |

例如放到 `/opt`（需要先给当前用户写权限）：

```bash
sudo mkdir -p /opt/test-agent && sudo chown "$USER" /opt/test-agent
DEPLOY_DIR=/opt/test-agent bash deploy.sh
```

> 首次部署完成后，务必把 `.env` 备份到本地。里面的 `APP_SECRET_KEY` 一旦更换，数据库里已保存的模型 Key、票房 Key 将全部无法解密；`AUTH_PASSWORD` 是登录口令，同样需要留存。
>
> 想自己指定数据库密码时，先在代码目录把 `.env.example` 复制为 `.env` 并改好 `DB_PASSWORD`，再执行 `./deploy.sh`；脚本不会覆盖已有值。

---

## 5. 访问与更新

部署成功后：

- 访问 `http://<公网IP>:8080`
- 应用日志：`docker compose -f docker-compose.prod.yml logs -f app`，或文件日志 `tail -f /mnt/logs/test-agent/app.log`
- 登录账号 `AUTH_USERNAME`（默认 `admin`），口令是首次部署生成的 `AUTH_PASSWORD`，见 `.env`；部署完成的日志也会打印一次。项目不再包含任何内置弱口令。

以后改了代码并 push 到 GitHub，服务器上只需要：

```bash
cd ~/test-agent
./deploy.sh
```

脚本会先同步到最新 `master`，再重建镜像并重启；数据库数据卷和 `.env` 都会保留。如果服务器上的代码目录有未提交改动，脚本会拒绝更新，先 `git status` 处理后再执行。

### 安全组

在 ECS 控制台放行入方向端口：

- 直接访问：放行 8080（来源建议限制为自己的 IP）
- 走 Nginx：放行 80 / 443，8080 不对外开放

### 使用阿里云 ACR 预构建镜像（可选）

服务器不装 JDK/Maven、也不想在服务器上构建时，可以先把镜像推到阿里云容器镜像服务（ACR），ECS 只负责拉取。

**一次性准备（ACR 控制台）**

1. 开通「容器镜像服务 → 个人版」，选择地域（与 ECS 同地域最好）。
2. 创建命名空间和镜像仓库，例如命名空间 `myns`、仓库 `test-agent`。
3. 在「访问凭证」里设置固定密码，登录时使用阿里云账号全名 + 该密码。

**本地构建并推送**（在开发机执行）

```bash
docker login --username=<阿里云账号全名> registry.cn-hangzhou.aliyuncs.com
docker tag test-agent:1.0.0 registry.cn-hangzhou.aliyuncs.com/<命名空间>/test-agent:1.0.0
docker push registry.cn-hangzhou.aliyuncs.com/<命名空间>/test-agent:1.0.0
```

**ECS 上改为拉取镜像**

在 `.env` 中填写镜像地址：

```bash
APP_IMAGE=registry.cn-hangzhou.aliyuncs.com/<命名空间>/test-agent:1.0.0
```

然后执行 `./deploy.sh`，脚本会自动 `docker compose pull app` 并用 `--no-build` 启动，不再本地构建。

补充说明：

- 仓库设为**公开**时 ECS 拉取无需登录；设为**私有**时先在 ECS 执行 `sudo docker login --username=<账号> registry.cn-hangzhou.aliyuncs.com`（用 `sudo docker` 就对应 root 的凭据）。
- ECS 与 ACR 同地域时，可用 VPC 地址 `registry-vpc.cn-hangzhou.aliyuncs.com/<命名空间>/test-agent:1.0.0`，走内网、速度快且不占公网流量。
- 推送时如提示 `denied`，通常是仓库未创建、命名空间不匹配或密码错误。

---

## 6. 开机自动恢复

Linux 上没有 Docker Desktop，自动恢复靠两层设置：

1. `sudo systemctl enable docker` —— 开机启动 Docker 守护进程
2. `restart: unless-stopped` —— Docker 起来后自动拉起 PostgreSQL 和应用容器

只要没有手动 `docker compose down`，服务器重启后不需要再执行 `up`。
注意：手动停掉的容器不会被 restart 策略重新拉起，需要重新 `docker compose -f docker-compose.prod.yml up -d`。

---

## 7. Nginx 反向代理（可选，推荐）

```nginx
server {
    listen 80;
    server_name your.domain.com;

    client_max_body_size 20m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 知识库问答是 SSE 流式输出，必须关闭缓冲并放宽超时
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
    }
}
```

---

## 8. 日常运维

```bash
# 查看容器状态
docker compose -f docker-compose.prod.yml ps

# 应用日志（容器标准输出）
docker compose -f docker-compose.prod.yml logs -f app

# 文件日志
tail -f /mnt/logs/test-agent/app.log

# 更新代码并重启（自动 git fetch + 重建 + 健康检查）
cd ~/test-agent && ./deploy.sh

# 停止（保留数据卷）
docker compose -f docker-compose.prod.yml down

# 数据库备份到数据盘
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U postgres test_agent | gzip > /mnt/test-agent/backup-$(date +%F).sql.gz

# 从备份恢复
gunzip -c /mnt/test-agent/backup-2026-11-06.sql.gz | \
  docker compose -f docker-compose.prod.yml exec -T postgres psql -U postgres -d test_agent
```

日志由 Logback 负责滚动：单文件 50MB、保留 15 天、总量上限 5GB，一般不需要额外清理。

---

## 9. 常见问题

**日志目录 Permission denied**
容器以 uid `10001` 运行，宿主机挂载目录必须可写：

```bash
sudo chown -R 10001:10001 /mnt/logs/test-agent /mnt/test-agent
```

**端口被占用**
改 `.env` 里的 `APP_PORT` 后重新 `up -d`；或先 `ss -lntp | grep 8080` 找到占用进程。

**应用一直 unhealthy**
先看 `docker compose -f docker-compose.prod.yml logs app`。常见原因：数据库密码与已有 `pgdata` 不一致（旧数据卷里还是老密码）、`APP_SECRET_KEY` 未设置。

**克隆代码失败（连接 GitHub 超时）**
服务器访问 GitHub 不稳定时，可在本地把仓库传到服务器，或指定镜像地址：

```bash
GIT_REPO=https://gitee.com/<你的镜像>/test-agent.git bash deploy.sh
```

也可以本地 `scp` 整个目录到服务器后进入目录执行 `./deploy.sh`。

**脚本提示“代码目录存在未提交的改动”**
服务器上的代码被手动改过。先 `cd ~/test-agent && git status` 确认，确实不需要的改动执行 `git checkout -- .` 丢弃后再运行脚本。

**更换 APP_SECRET_KEY 后页面提示 Key 解密失败**
说明数据库里存的是用旧密钥加密的密文。要么改回旧密钥，要么在「模型配置」页面重新填写所有 API Key。

**镜像拉取超时**
配置第 3 节的镜像加速器；或在国内机器上先把镜像推到阿里云 ACR，再改 compose 里的 image 地址。

**首次构建下载 Maven 依赖很慢 / 每次部署都重新下载**
`Dockerfile` 构建阶段默认走阿里云 Maven 镜像，并且用 BuildKit 缓存挂载把 Maven 本地仓库持久化在构建缓存里：

- 只改 Java/前端代码：依赖层直接命中缓存，不会重新下载
- 改 `pom.xml`：只补下新增依赖，已有依赖继续用缓存
- 只有第一次构建、或手动清理过构建缓存，才会完整下载

`deploy.sh` 已强制 `DOCKER_BUILDKIT=1`。**不要执行 `docker builder prune` 或 `docker system prune`**，它们会清空这层缓存；磁盘清理用 `docker image prune -f` 即可（只删悬空镜像，不影响构建缓存）。如需换回官方 Maven 仓库：

```bash
docker build --build-arg MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2 -t test-agent:1.0.0 .
```

如果连构建都不想跑，可以本地构建后推到阿里云 ACR，服务器 `.env` 填 `APP_IMAGE` 只拉镜像（见上文 ACR 小节）。

**系统盘越来越满**
数据库命名卷在 `/var/lib/docker`。可把 Docker 数据目录迁到数据盘（`/etc/docker/daemon.json` 设置 `data-root`）。日常清理用 `docker image prune -f`；`docker builder prune` 会清空 Maven 依赖缓存，非必要不要执行。

**忘记管理员口令 / 想改口令**

```bash
grep '^AUTH_PASSWORD' ~/test-agent/.env
# 修改后重建应用容器使新口令生效（sys_user 中的 BCrypt 哈希会在启动时同步）
cd ~/test-agent && docker compose -f docker-compose.prod.yml up -d --force-recreate app
```

也可以通过 `AUTH_PASSWORD_HASH` 直接提供 BCrypt 哈希，避免明文口令落盘。

**上了 HTTPS（Nginx + 证书）之后登录不了**

在 `.env` 中设置 `AUTH_COOKIE_SECURE=true` 并重建应用：Cookie 会带上 `Secure` 属性。
反过来，纯 HTTP 访问时必须保持 `false`，否则浏览器不会回传会话 Cookie。

**模型服务地址是内网 / 本地 Ollama**

应用默认拒绝 `baseUrl` 指向内网或云元数据地址（SSRF 防护）。确需访问内网模型服务时，
在 `.env` 设置 `OUTBOUND_ALLOW_PRIVATE_HOSTS=true`，或用 `OUTBOUND_ALLOWED_HOSTS` 精确放行域名，
然后重建应用容器。
