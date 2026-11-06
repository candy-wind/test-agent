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

## 4. 获取代码并配置

```bash
git clone https://github.com/candy-wind/test-agent.git
cd test-agent
chmod +x deploy.sh
```

推荐直接运行一次 `./deploy.sh`：它会创建 `.env` 并自动生成随机 `APP_SECRET_KEY`，然后提示你先改数据库密码。

编辑 `.env`：

```bash
vim .env
```

必须修改的项：

- `DB_PASSWORD`：换成强密码。
- `APP_SECRET_KEY`：脚本已随机生成，**保持不动并额外备份一份**。它用于加密数据库里的模型 Key 和票房 Key，换了之后这些 Key 全部解不开。
- `APP_PORT`：默认 8080；如果前面挂 Nginx，可保持默认并让 Nginx 反代。

---

## 5. 启动

```bash
./deploy.sh
```

等价于手动执行：

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

首次构建要在容器里下载 Maven 依赖，耗时几分钟。启动后：

- 访问 `http://<公网IP>:8080`
- 日志 `tail -f /mnt/logs/test-agent/app.log`
- 登录账号仍为 `admin / admin123`（`application.yml` 中的演示配置，上线前建议改掉）

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

# 更新代码后重启
git pull
docker compose -f docker-compose.prod.yml up -d --build

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

**更换 APP_SECRET_KEY 后页面提示 Key 解密失败**
说明数据库里存的是用旧密钥加密的密文。要么改回旧密钥，要么在「模型配置」页面重新填写所有 API Key。

**镜像拉取超时**
配置第 3 节的镜像加速器；或在国内机器上先把镜像推到阿里云 ACR，再改 compose 里的 image 地址。

**首次构建下载 Maven 依赖很慢**
`Dockerfile` 构建阶段已默认使用阿里云 Maven 镜像（`https://maven.aliyun.com/repository/public`），依赖会缓存在镜像层，后续只改代码不会重复下载。如需换回官方仓库：

```bash
docker build --build-arg MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2 -t test-agent:1.0.0 .
```

**系统盘越来越满**
数据库命名卷在 `/var/lib/docker`。可把 Docker 数据目录迁到数据盘（`/etc/docker/daemon.json` 设置 `data-root`），或定期执行 `docker system prune` 清理无用镜像。
