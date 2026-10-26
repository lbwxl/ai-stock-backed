# 本地后端

本仓库克隆自 https://github.com/yangzongzhuan/RuoYi-Vue ，使用 `v3.9.0` 标签（`fa88922637fa674026ef0a6f3db5f46a3d946945`），与 `/Users/wxl/aigupiaofenxihoutaixitong` 前端声明的版本一致。

后端技术栈为 Spring Boot 2.5.15、Spring Security、MyBatis、MySQL、Redis 和 JWT。

## 启动与停止

```bash
cd /Users/wxl/ai-stock-backend
./start-local.sh
```

自 2026-10-26 起，本机 MySQL 和 Redis 统一由 Homebrew 管理，数据存放在全局目录；项目 `.local/` 只保留后端私有配置、上传文件及应用日志。启动脚本通过 `brew services start` 启动依赖，复用已有数据库，不初始化数据、不重新导入 SQL；后端启动前会重新构建当前代码。


- 后端：http://127.0.0.1:18080
- 前端：http://localhost:1024
- 初始管理员：`admin` / `admin123`，登录时填写页面验证码。
- 日志：`.local/logs/backend-console.log`
- 数据库：`127.0.0.1:3306/ry-vue`，使用用户提供的 SQL 及后续迁移，共 35 张表
- Redis：`127.0.0.1:6379`
- 上传文件和本地凭据位于忽略提交的 `.local/` 中，数据库数据在项目外。

默认只停止当前项目的后端，保留数据库和 Redis：

```bash
./stop-local.sh
```

需要同时停止后端和共享的 Homebrew 数据库、Redis 时：

```bash
./stop-local.sh --all
```

停止脚本校验后端归属；`--all` 会额外调用 `brew services stop` 停止共享依赖，数据保留。IDEA 与脚本只保留一个后端进程。

## Homebrew 服务管理（2026-10-26）

本机依赖已从项目独立进程迁移到 Homebrew 的用户级服务，项目移除或切换工作树不再影响数据库数据位置。IDEA 仍连接 `127.0.0.1:3306/ry-vue` 和 `127.0.0.1:6379`，账号、密码和 JWT 密钥保持不变。

```bash
# 启动并设置用户登录后自动启动
brew services start mysql@8.4
brew services start redis

# 查看状态
brew services list

# 停止并取消登录自动启动；停止前先结束使用数据库的后端
brew services stop redis
brew services stop mysql@8.4
```

只想临时运行可用 `brew services run`；重启可用 `brew services restart`。项目的 `./start-local.sh --deps-only` 和桌面“本地数据库”小工具也通过 Brew 管理服务。

- MySQL 数据：`/opt/homebrew/var/mysql`；Socket：`/tmp/mysql.sock`；配置：`/opt/homebrew/etc/my.cnf`。
- Redis 数据：`/opt/homebrew/var/db/redis`；配置：`/opt/homebrew/etc/redis.conf`；已保留 AOF 持久化。
- 数据库服务日志：`/opt/homebrew/var/log/mysql.log`、`/opt/homebrew/var/log/redis.log`。
- 迁移前的完整数据目录、SQL 逻辑备份和旧 Brew 目录保存在 `~/Library/Application Support/ai-stock-services/backups/` 的时间戳目录中，权限为当前用户私有。迁移校验覆盖了全部 35 张业务表的行数。

以下历史迁移记录中关于 `.local/mysql` 和 `.local/redis` 的路径已被本节取代。

## 依赖与重新编译

### IDEA 启动重新克隆的项目

业务代码相同并不代表本地配置相同：`.local/` 被 Git 忽略，克隆不会带上数据库凭据、上传文件或数据库数据。直接运行 `RuoYiApplication` 只激活默认 `druid` 配置，会使用 `application-druid.yml` 中的示例 `root / password`。

本机 `/Users/wxl/ai-stock-backend` 已准备私有 `.local/application-local.yml`，数据库与 Redis 使用全局 Homebrew 服务，无需重新导入 SQL。先运行以下命令启动依赖，再在 IDEA 运行配置中选择 **RuoYi-local**，使用 `ruoyi-admin` 模块启动：

```bash
./start-local.sh --deps-only
```

若脚本启动的后端还在运行，先执行 `./stop-local.sh` 释放端口。IDEA 工作目录为项目根目录，参数为：

```text
--spring.profiles.active=druid,local --spring.config.additional-location=file:./.local/
```

启动日志应同时出现 `druid`、`local` 两个 profile，后端端口为 `18080`。同一时间只运行一个后端实例。当前 MySQL 数据目录为 `/opt/homebrew/var/mysql`，Redis 数据目录为 `/opt/homebrew/var/db/redis`；只有上传目录 `.local/uploads` 属于本项目。

其他机器克隆后，需要准备 `.local/application-local.yml` 和对应的全局数据库、Redis 服务；启动脚本不会新建空库。运行配置不包含数据库密码，私有配置不得提交。

MySQL 8.4 与 Redis 使用 Homebrew 安装。当前 Java 使用本机 PyCharm 自带的 JBR 25，已用于构建和启动；脚本也支持通过 `RUOYI_JAVA_HOME` 指定 Java 路径，或自动使用已安装的 Homebrew JDK 17。Maven 3.9.9 位于 `/Users/wxl/.local/share/aigupiao-tools/apache-maven-3.9.9`。

```bash
JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home \
  /Users/wxl/.local/share/aigupiao-tools/apache-maven-3.9.9/bin/mvn \
  -B -ntp -s local-maven-settings.xml -DskipTests package
```

后端代码修改后，需要停止服务、重新编译，再启动。

前端使用 `.env.development.local` 设置 `VUE_APP_BACKEND_URL=http://127.0.0.1:18080` 和 `VUE_APP_IMG_API=/dev-api`，由开发服务器将 `/dev-api` 转发到本地后端：

```bash
cd /Users/wxl/aigupiaofenxihoutaixitong
NODE_OPTIONS=--openssl-legacy-provider npm run dev -- --port 1024
```

## 在线接口文档与上传

- Swagger UI：http://localhost:1024/dev-api/swagger-ui/index.html
- OpenAPI 3 JSON：http://localhost:1024/dev-api/v3/api-docs
- Swagger 2 JSON：http://localhost:1024/dev-api/v2/api-docs

文档扫描项目内全部控制器，包含系统、监控、定时任务、代码生成、AI 业务和通用文件接口。使用上述前端代理地址进行在线调试，文档路径中的 `/dev-api` 与前端代理配置一致。

需要认证的接口在 Swagger 页面点击 **Authorize**，填写 `Bearer <后台登录返回的 token>`。`/captchaImage`、`/login`、`/register` 本身不要求 JWT；管理接口仍执行原有权限校验。`GET /webui/config/current` 需要 JWT，只返回当前登录用户的配置，不要求配置管理菜单权限。原 `POST /webui/auth/in` 已注释停用。

在 **通用文件上传下载** 分组中：

- `POST /common/upload`：点击 **Try it out**，在 `file` 字段选择单个文件。
- `POST /common/uploads`：点击 **Try it out**，在 `files` 字段点击 **Add item** 添加文件选择框，逐个选择文件；同名 multipart 字段依次提交。
- 上传响应的 JSON 字段、示例值和下载路径在各接口的 Responses 中展示。

默认每个文件最大 10MB，整个 multipart 请求最大 20MB；文件类型仍使用后端白名单，不支持分片上传或断点续传。

## 二次开发范围

判断依据包括前端 `package.json` 的 `ruoyi` 名称、`3.9.0` 版本和官方仓库链接，以及 `/login`、`/captchaImage`、`/getInfo`、`/getRouters` 和 `/system/*` 接口。

已根据用户提供的 `/Users/wxl/Downloads/ruoyi-admin.jar` 还原二开源码：新增 `ruoyi-webui` Maven 模块、5 个业务控制器和登录 DTO，补齐 `/webui/config/*`、`/webui/model/*`、`/webui/service/*`、`/webui/search/*`、`POST /webui/auth/in` 以及 `GET /system/user/select/list`，共 29 个 HTTP 方法与路径组合。

这些接口使用当前本地 `ry-vue` 数据库。当前已统一使用原生 `POST /login` 获取 JWT，并通过 `GET /webui/config/current` 获取当前用户的 AI、模型及搜索配置；`POST /webui/auth/in` 的映射已注释停用并移出匿名白名单。AI 供应商和搜索供应商配置由本地数据决定；后端通过 `/webui/ai/chat/completions` 执行模型调用，搜索维持原流程。

还原依据、必要适配及验证结果见 [WEBUI_RESTORE.md](WEBUI_RESTORE.md)。可重新执行集成检查：

```bash
cd /Users/wxl/ai-stock-backend
node tests/verify-webui.mjs
BASE_URL=http://localhost:1024/dev-api node tests/verify-webui.mjs
```

测试仅写入唯一命名的测试记录并在结束时删除；现有业务行不参与修改。测试会产生正常的登录及操作日志。

## 分析工具登录流程

`/Users/wxl/ai-stock-analysis-tool` 使用 `NUXT_PUBLIC_API_BASE_URL` 指定业务后端，默认 `http://127.0.0.1:18080`。

1. `GET /captchaImage` 获取系统验证码（根据 `captchaEnabled` 显示或隐藏）。
2. `POST /login` 提交用户名、密码、验证码 `code` 和 `uuid`，获取原生 JWT。
3. 浏览器将 JWT 保存到独立的 `research-token` Cookie，业务请求统一通过 `Authorization: Bearer <token>` 发送。
4. `GET /webui/config/current` 根据 JWT 身份查询数据库，忽略外部传入的用户 ID；没有配置时返回身份和空配置。
5. 业务响应 `code: 401` 或 HTTP 401 时清除本地 token 与供应商配置，返回登录页；403 保留登录状态。退出调用原生 `/logout` 使 token 失效。

AI 分析请求通过业务 JWT 访问后端，由后端读取当前用户的供应商密钥；搜索服务维持原有调用流程。旧固定管理员登录逻辑已移除。

认证集成检查（验证码只读取本地 Redis 中本次测试生成的值，不关闭系统验证码）：

```bash
node tests/verify-auth.mjs
```


## AI 接口 404 与供应商重定向排查

2026-10-22 实际排查确认：`18080` 被旧仓库 JAR 占用时，新项目会报端口冲突并退出，前端调用新增 AI 接口返回 404。可用 `lsof -nP -iTCP:18080 -sTCP:LISTEN` 找到 PID，再用 `ps -p <PID> -o command=` 确认运行目录。停止旧后端后，只启动当前项目。

DeepSeek 服务地址应使用 `https://api.deepseek.com/v1`。原 HTTP 地址会返回 302；后端不会携带供应商密钥自动跟随跳转，应在后台服务管理中直接配置最终 HTTPS 地址。

本次已切换到当前项目的 JAR，后台启动日志为 `.local/logs/backend-console.log`，PID 保存在 `.local/backend.pid`；如果改用 IDEA 启动，应先停止这个后台进程以释放端口。两种方式都复用相同私有配置，不重新初始化数据库。


## 2026-10-22 本地数据迁移

已从恢复后的旧项目复制 MySQL 数据、Redis 持久化数据和上传文件，并逐文件校验。迁移前的独立备份位于 `.local/backups/migration-20261022/`；旧目录保持原样。私有后端配置、Redis 配置、数据库进程的路径均已改为本项目。旧项目可以移除，当前服务不再使用它的数据目录。

迁移前 MySQL 因目录移入废纸篓而异常退出，复制后的数据库已完成 InnoDB/XA 恢复，34 张业务表、5 条用户配置及 8 个用户均可读取。Redis 迁移前的 19 个键和登录会话保留。


## 提示词维护

系统和业务提示词位于 `ruoyi-webui/src/main/resources/prompts/ai/`，包括补充问题、搜索词、搜索结果分析和报告四个场景。前端只提交业务数据，后端构造消息、输出规则和生成参数。调整模板后停止后端、重新构建再启动即可。接口参数见 [AI_ANALYSIS_API.md](AI_ANALYSIS_API.md)。

## 文件分析私有上传

2026-10-22：当前本地配置已启用 MinIO，地址与凭据通过环境变量配置。本机默认值位于被 Git 忽略的 `.local/backend.env`（权限 `600`），由 `start-local.sh` 传给 Java，已导出的环境变量优先。变量包括 `FILE_ANALYSIS_STORAGE_TYPE=minio`、`MINIO_ENDPOINT`、`MINIO_PUBLIC_ENDPOINT`（可选）、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET`，模板见 [backend.env.example](backend.env.example)。修改后重启后端。IDEA 和服务器直接启动时需在运行环境注入这些变量。新文件存入 `ai-file` 桶的 `file-analysis/<用户ID>/<随机UUID>`，元数据也随对象保存，不依赖本地文件目录或 Cloudflare/ngrok 隧道。旧本地上传再次分析时迁移原文件并保留原有效期。

可运行 `node tests/run-minio-verification.mjs --live --model` 验证真实 MinIO 与当前用户模型，运行 `node tests/verify-minio-upload.mjs --live` 验证运行中的后端接口。前者 `--model` 会调用真实模型 API。测试不会修改用户模型配置，结束后删除自己的测试对象和会话。生产部署需配置私有桶和 HTTPS 文件入口，详见 [FILE_ANALYSIS_API.md](FILE_ANALYSIS_API.md)。

以下隧道说明仅适用于 `storage-type: local`：

原始文件默认保存在当前项目 `.local/ai-files/`，按用户和随机 UUID 隔离，默认有效期 24 小时。此目录不挂在 `/profile` 等公开资源路径下。分析接口验证文件归属后生成临时签名 URL，模型服务直接下载原文件，模型请求不内嵌字节。生产配置 `webui.file-analysis.public-base-url`；本地可用 `./start-file-tunnel.sh` 启动仅暴露签名下载接口的 ngrok 隧道，也可使用 `./start-file-tunnel.sh cloudflare` 启动 Cloudflare 临时隧道，执行 `./stop-file-tunnel.sh` 停止。存储路径和保留时间可通过 `webui.file-analysis.storage-path`、`retention-seconds` 配置，详见 [FILE_ANALYSIS_API.md](FILE_ANALYSIS_API.md)。

文件隧道由本项目守护进程维护：进程退出、临时域名撤销或公网健康检查失败时自动重建，并更新 `.local/file-tunnel-url`；状态见 `.local/file-tunnel-status.json`。分析前校验文件 URL，只做 HEAD 网络检查；不可达时返回明确的 503，恢复后可直接重试已有文件。

## 文件分析日志

运行 `tail -f .local/logs/file-analysis.log` 查看文件分析各阶段；完整控制台日志为 `.local/logs/backend-console.log`，系统错误为 `.local/logs/sys-error.log`。使用上传或分析响应中的 `requestId` 检索同一次请求。日志不包含密钥、JWT、完整签名 URL 或文件正文。当前上传响应返回完整的 MinIO 签名 `fileUrl`，另有 `fileId`、`urlExpiresAt` 和 `requestId`，不再返回 MinIO 文件的相对地址。
