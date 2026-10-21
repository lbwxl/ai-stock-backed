# 本地后端

本仓库克隆自 https://github.com/yangzongzhuan/RuoYi-Vue ，使用 `v3.9.0` 标签（`fa88922637fa674026ef0a6f3db5f46a3d946945`），与 `/Users/wxl/aigupiaofenxihoutaixitong` 前端声明的版本一致。

后端技术栈为 Spring Boot 2.5.15、Spring Security、MyBatis、MySQL、Redis 和 JWT。

## 启动与停止

```bash
cd /Users/wxl/RuoYi-Vue
./start-local.sh
```

首次启动会初始化项目专用的 MySQL 数据目录，导入用户提供的 `/Users/wxl/Downloads/ry-vue.sql`，并编译后端。可以通过 `RUOYI_SEED_SQL` 指定其他本地 SQL 文件。再次启动会保留现有数据，不会重复导入。

- 后端：http://127.0.0.1:18080
- 前端：http://localhost:1024
- 初始管理员：`admin` / `admin123`，登录时填写页面验证码。
- 日志：`.local/logs/backend-console.log`
- 数据库：`127.0.0.1:3306/ry-vue`，使用用户提供的 SQL，共 34 张表
- Redis：`127.0.0.1:6379`
- 数据、上传文件和随机生成的本地凭据位于忽略提交的 `.local/` 中。

```bash
./stop-local.sh
```

停止脚本只停止本项目启动的后端、Redis 和 MySQL。服务没有注册为系统开机启动项。

## 依赖与重新编译

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

这些接口使用当前本地 `ry-vue` 数据库。当前已统一使用原生 `POST /login` 获取 JWT，并通过 `GET /webui/config/current` 获取当前用户的 AI、模型及搜索配置；`POST /webui/auth/in` 的映射已注释停用并移出匿名白名单。AI 供应商和搜索供应商配置由本地数据决定，管理后端本身不发起模型推理或搜索请求。

还原依据、必要适配及验证结果见 [WEBUI_RESTORE.md](WEBUI_RESTORE.md)。可重新执行集成检查：

```bash
cd /Users/wxl/RuoYi-Vue
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

AI 与搜索供应商请求继续使用各自的 API Key，不使用业务 JWT。旧固定管理员登录逻辑已移除。

认证集成检查（验证码只读取本地 Redis 中本次测试生成的值，不关闭系统验证码）：

```bash
node tests/verify-auth.mjs
```
