# test-agent 启动指南

本文说明如何在本地启动、停止 test-agent，以及常见问题的处理。

> 部署到阿里云 ECS（Linux）请使用容器化方案，见 [deploy-aliyun.md](deploy-aliyun.md)；本文的 PowerShell 脚本仅适用于 Windows。

---

## 1. 前置条件

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | **25** | 系统默认 Java 可能是 1.8，脚本会自动切换到 JDK 25 |
| Maven | 3.9+ | 需在 `PATH` 中，脚本用 `mvn` 构建/启动 |
| Docker Desktop | 任意较新版本 | 用于运行 PostgreSQL 16 + pgvector |
| 端口 | 8080、5432 | 应用默认 8080，数据库默认 5432 |

> 如果已有外部 PostgreSQL（且安装了 pgvector 扩展），可以不启动 Docker，使用 `-SkipDb` 并配置 `DB_URL` 等环境变量。

---

## 2. 快速开始

在项目根目录 `C:\Users\13605\IdeaProjects\test-agent` 下：

```powershell
.\start.ps1
```

脚本会依次完成：

1. 自动定位 JDK 25，设置 `JAVA_HOME` / `PATH`
2. 检查 Docker 守护进程（未启动时给出提示）
3. 执行 `docker compose up -d` 启动 PostgreSQL，并等待数据库就绪
4. 检查目标端口是否被占用
5. 以开发模式运行 `mvn spring-boot:run`

启动成功后访问：<http://localhost:8080>

也可以在资源管理器里双击 `start.bat`（内部同样调用 `start.ps1`，失败时会停留窗口）。

---

## 3. 脚本一览

| 脚本 | 作用 |
| --- | --- |
| `start.ps1` | 启动服务（JDK、Docker、数据库、端口检查） |
| `start.bat` | Windows 双击入口，参数原样传给 `start.ps1` |
| `stop.ps1` | 按端口停止服务 |
| `run-mvn.ps1` | 用 JDK 25 执行任意 Maven 命令 |
| `docker-compose.yml` | PostgreSQL + pgvector 容器定义 |

### start.ps1 参数

| 参数 | 说明 |
| --- | --- |
| 无参数 | 开发模式：`mvn spring-boot:run`，改代码后重新运行即可 |
| `-Build` | 先 `mvn clean package -DskipTests`，再以 jar 方式启动 |
| `-Jar` | jar 方式启动；jar 不存在或比源码旧时自动打包 |
| `-Jar -NoBuild` | 只运行已有 jar，绝不重新打包（适合快速重启） |
| `-SkipDb` | 跳过 Docker/数据库检查（使用外部数据库时） |
| `-StartDocker` | Docker 未运行时尝试自动启动 Docker Desktop |
| `-Port 8081` | 指定应用端口，默认 8080 |
| `-Help` | 显示帮助 |

示例：

```powershell
# 开发模式（默认）
.\start.ps1

# 重新打包后以 jar 启动（推荐在交付/演示时使用）
.\start.ps1 -Build

# 源码没变时快速重启
.\start.ps1 -Jar -NoBuild

# 换端口，不检查 Docker
.\start.ps1 -Port 8081 -SkipDb

# 自动拉起 Docker Desktop
.\start.ps1 -StartDocker
```

### stop.ps1 参数

| 参数 | 说明 |
| --- | --- |
| 无参数 | 停止占用 8080 的进程（会询问确认） |
| `-Force` | 不询问，直接结束 |
| `-Port 8081` | 指定端口 |
| `-Port 8080,8090` | 一次停止多个端口 |

```powershell
.\stop.ps1
.\stop.ps1 -Port 8081 -Force
```

### run-mvn.ps1

自动切换到 JDK 25 后执行 Maven，避免直接敲 `mvn` 时用到 Java 8：

```powershell
.\run-mvn.ps1 -v
.\run-mvn.ps1 clean package -DskipTests
.\run-mvn.ps1 test
.\run-mvn.ps1 spring-boot:run
.\run-mvn.ps1 test -Dtest=TextChunkerTest
```

---

## 4. 开发模式与 jar 模式

| | 开发模式（默认） | jar 模式（`-Build` / `-Jar`） |
| --- | --- | --- |
| 启动方式 | `mvn spring-boot:run` | `java -jar target/test-agent-1.0.0.jar` |
| 是否自动编译 | 每次启动都会编译 | 仅在 jar 缺失/过期或 `-Build` 时打包 |
| 适合场景 | 改代码、调试 | 演示、部署、快速重启 |

jar 位置：`target/test-agent-1.0.0.jar`。

---

## 5. 数据库与 Docker

### 容器信息

- 镜像：`pgvector/pgvector:pg16`
- 容器名：`test-agent-pg`
- 数据库：`test_agent`
- 账号/密码：`postgres` / `postgres`
- 端口：`5432`
- 数据卷：`pgdata`（`docker compose down` 不会删除，`down -v` 才会）

### 表结构

- 由 Flyway 自动管理，迁移脚本在 `src/main/resources/db/migration/`
- `spring.jpa.hibernate.ddl-auto=none`，Hibernate 不会自动建表
- 应用启动时自动执行未应用的迁移；已应用的迁移不要修改（校验和会不一致）

查看容器状态：

```powershell
docker compose ps
docker compose logs -f postgres
```

### 使用外部 PostgreSQL

1. 准备一个已安装 `vector` 扩展的 PostgreSQL 16 实例
2. 设置环境变量后启动：

```powershell
$env:DB_URL      = "jdbc:postgresql://主机:5432/test_agent"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "你的密码"
.\start.ps1 -SkipDb
```

---

## 6. 常用配置

配置文件：`src/main/resources/application.yml`。下表为常用环境变量（括号内是默认值）：

| 环境变量 | 作用 |
| --- | --- |
| `DB_URL` | 数据库连接（`jdbc:postgresql://localhost:5432/test_agent`） |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号密码（`postgres` / `postgres`） |
| `APP_SECRET_KEY` | 模型 API Key 的加密密钥（**生产必须设置**，默认 `dev-only-change-me`） |
| `KB_STORAGE_DIR` | 知识库上传文件目录（`./data/kb`） |
| `KB_VECTORIZATION_ENABLED` | 是否启用知识库向量化（默认 `false`） |
| `APIZERO_API_KEY` | 极数本源票房接口的 API Key（匿名额度用尽后必须配置） |
| `LOG_DIR` | 日志根目录（`/mnt/logs`） |
| `LOG_SERVICE_NAME` | 日志子目录，默认取服务名（`test-agent`） |

> 模型供应商、模型和 API Key 不再写在 `application.yml` 里，统一在「模型配置」页面维护；Key 使用 `APP_SECRET_KEY` 加密后存入数据库。票房数据源的 API Key 也在同一页面的「外部数据源」区域保存，优先于 `APIZERO_API_KEY` 环境变量。

知识库相关参数（`platform.knowledge.*`）：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `vectorization-enabled` | false | 关闭时上传只做解析/切分，检索走关键词匹配 |
| `embedding-dimension` | 1024 | 启用向量化时必须与 `kb_embedding` 的 `vector(1024)` 一致 |
| `embedding-batch-size` | 16 | 向量化批大小 |
| `default-top-k` / `max-top-k` | 5 / 20 | 检索返回条数 |
| `max-context-chars` | 8000 | 拼入提示词的检索内容上限 |
| `max-history-messages` | 12 | RAG 问答携带的历史消息数 |

其他限制：

- 单文件上传上限 20MB（`spring.servlet.multipart.max-file-size`）
- 日志文件：`/mnt/logs/test-agent/app.log`（按天滚动，保留 15 天，级别 INFO；可用 `LOG_DIR`、`LOG_SERVICE_NAME` 覆盖目录和服务名）

---

## 7. 访问地址与登录

| 页面 | 地址 |
| --- | --- |
| 首页 / AI 对话 | <http://localhost:8080/> |
| 知识库 | <http://localhost:8080/kb.html> |
| 模型配置 | <http://localhost:8080/platform.html> |
| 登录页 | <http://localhost:8080/login.html> |

默认登录账号（`auth.username` / `auth.password`）：

```text
admin / admin123
```

> 这是演示账号，生产环境请替换为真实认证方式。

---

## 8. 停止服务

```powershell
# 会先询问
.\stop.ps1

# 直接停止
.\stop.ps1 -Force

# 停止其他端口
.\stop.ps1 -Port 8081 -Force
```

如果只是想让数据库停止：

```powershell
docker compose stop       # 停止容器，保留数据
docker compose down       # 删除容器，保留数据卷
docker compose down -v    # 删除容器和数据卷（数据会丢失）
```

---

## 9. 常见问题

### 提示“禁止运行脚本 / 无法加载文件 start.ps1”

这是 Windows 的 PowerShell 执行策略限制。两种解决方式：

- 双击 `start.bat`（内部已使用 `-ExecutionPolicy Bypass`）
- 或在终端显式指定：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

只想临时放开当前用户策略时，也可以执行：

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

### 端口已被占用

```
[ERROR] 端口 8080 已被占用：PID 12345 (java)
```

执行 `.\stop.ps1 -Port 8080`，或换端口启动：`.\start.ps1 -Port 8081`。

> 用 IDE 停止进程有时只停了 Maven、没停 Java 子进程，此时用 `stop.ps1` 按端口清理最可靠。

### 提示未找到 JDK 25

安装 JDK 25，或把 `JAVA_HOME` 指向 JDK 25 后重开终端。脚本会检查：

1. `C:\Program Files\Java\jdk-25.0.3`
2. 当前 `JAVA_HOME`
3. `C:\Program Files\Java`、`Eclipse Adoptium`、`Microsoft` 下名称含 `25` 的目录
4. `PATH` 中的 `java -version` 是否 ≥ 25

### Docker 守护进程未运行

打开 Docker Desktop，或：

```powershell
.\start.ps1 -StartDocker
```

仅使用外部数据库时加 `-SkipDb`。

### 5432 端口被本机已有 PostgreSQL 占用

`docker compose up -d` 会失败。两种处理方式：

- 停掉本机 PostgreSQL 服务；或
- 修改 `docker-compose.yml` 的端口映射（如 `"5433:5432"`），并设置 `DB_URL=jdbc:postgresql://localhost:5433/test_agent`

### Flyway 校验失败（checksum mismatch）

通常是因为修改了已经执行过的迁移脚本。正确做法是新增 `V2__xxx.sql`，不要改 `V1__platform_init.sql`。

本地演示环境确实需要重置时（**会清空数据**）：

```powershell
docker compose down -v
docker compose up -d
```

### 知识库上传/检索提示“未配置 EMBEDDING 模型”

默认 `platform.knowledge.vectorization-enabled=false`，知识库使用关键词检索，上传文档不需要 embedding 模型，也不会调用向量化接口。

如果需要语义检索，再按以下步骤启用：

1. 在 **模型配置** 添加供应商（OpenAI 兼容接口，含 `baseUrl` 与 `apiKey`）
2. 添加模型，能力勾选 `EMBEDDING`，并确保输出 1024 维
3. 在“用途路由”中把 `EMBEDDING` 指向该模型
4. 设置 `KB_VECTORIZATION_ENABLED=true` 后重启应用
5. 对已有文档执行“重建”，写入向量

### 向量维度不一致

报错类似“返回维度 x，期望 1024”。`kb_embedding` 当前固定为 `vector(1024)`，请换用输出 1024 维的 embedding 模型，或通过新的迁移脚本调整维度。

### PDF 上传后提示需要 OCR

当前只提取 PDF 的文本层。扫描件（纯图片 PDF）需要先 OCR 再上传。

### 页面被浏览器缓存

静态资源已配置 `no-cache`，仍异常时强制刷新（`Ctrl+F5`）或清缓存。

---

## 10. 目录速查

```
test-agent/
├─ start.ps1              # 启动
├─ start.bat              # Windows 双击入口
├─ stop.ps1               # 按端口停止
├─ run-mvn.ps1            # JDK 25 下的 Maven 包装
├─ docker-compose.yml     # PostgreSQL + pgvector
├─ data/kb/               # 知识库上传的原文件（已 gitignore）
├─ /mnt/logs/test-agent/  # 运行日志（保留 15 天）
├─ target/                # 构建产物
├─ src/main/resources/
│  ├─ application.yml
│  ├─ db/migration/       # Flyway 迁移
│  └─ static/             # index.html、kb.html、platform.html、login.html
└─ docs/
   ├─ platform-architecture.md
   └─ startup-guide.md    # 本文
```
