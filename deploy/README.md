# JAR 部署环境变量

完整模板见 `backend.env.example`。复制到服务器 `/opt/ai-stock/config/backend.env` 并填写实际值。该模板中的标准 Spring 环境变量可直接覆盖 JAR 内配置，不需要额外的 `application-prod.yml`，也不需要重新打包 JAR。前面示例中的 `DB_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET` 是自定义简写，只有在配套 YAML 引用了这些变量时才生效；本模板统一使用完整属性名。

数据库 URL、账号和密码、Redis 连接信息、JWT 密钥、MinIO 地址和凭据必须与服务器实际环境一致。AI 供应商和模型 API Key 由后台的用户配置维护，不需要在本环境文件中额外设置。

`MINIO_PUBLIC_ENDPOINT` 可省略，默认使用 `MINIO_ENDPOINT`；最终下载地址必须能被模型服务和用户浏览器访问。MinIO 地址填写对象接口端口（当前为 9000），不是控制台端口 9001。

以下命令假设已创建部署目录并上传 `ruoyi-admin.jar`，由有目录写权限的运行用户执行：

```bash
cd /opt/ai-stock
chmod 600 config/backend.env

set -a
. ./config/backend.env
set +a

mkdir -p "$RUOYI_LOG_PATH" "$RUOYI_PROFILE"
nohup java -jar ruoyi-admin.jar > "$RUOYI_LOG_PATH/backend-console.log" 2>&1 &
```

JAR 不会自动加载 env 文件；上述 shell 命令负责加载。如果使用 systemd，配置 `EnvironmentFile=/opt/ai-stock/config/backend.env` 和 `WorkingDirectory=/opt/ai-stock`，再启动 Java。修改变量后需要重新启动后端；通过 shell 启动时应重新加载 env 文件。

本模板仅配置后端。用户前台 Nuxt 单独配置 `NUXT_PUBLIC_API_BASE_URL`；后台管理端生产请求前缀默认为 `/prod-api`，由 Nginx 转发至后端。
