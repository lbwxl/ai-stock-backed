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

## 二次开发范围

判断依据包括前端 `package.json` 的 `ruoyi` 名称、`3.9.0` 版本和官方仓库链接，以及 `/login`、`/captchaImage`、`/getInfo`、`/getRouters` 和 `/system/*` 接口。

已根据用户提供的 `/Users/wxl/Downloads/ruoyi-admin.jar` 还原二开源码：新增 `ruoyi-webui` Maven 模块、5 个业务控制器和登录 DTO，补齐 `/webui/config/*`、`/webui/model/*`、`/webui/service/*`、`/webui/search/*`、`POST /webui/auth/in` 以及 `GET /system/user/select/list`，共 29 个 HTTP 方法与路径组合。

这些接口使用当前本地 `ry-vue` 数据库。`/webui/auth/in` 按原包逻辑校验账号密码并返回该用户的 AI、模型及搜索配置；后台管理登录仍使用 `/login` 获取 JWT。AI 供应商和搜索供应商配置由本地数据决定，管理后端本身不发起模型推理或搜索请求。

还原依据、必要适配及验证结果见 [WEBUI_RESTORE.md](WEBUI_RESTORE.md)。可重新执行集成检查：

```bash
cd /Users/wxl/RuoYi-Vue
node tests/verify-webui.mjs
BASE_URL=http://localhost:1024/dev-api node tests/verify-webui.mjs
```

测试仅写入唯一命名的测试记录并在结束时删除；现有业务行不参与修改。测试会产生正常的登录及操作日志。
