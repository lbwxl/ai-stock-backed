# 后端启动脚本

将 `setup-env.sh` 与 `ruoyi-admin.jar` 放在同一目录，直接编辑脚本顶部的配置区，填写 MySQL、Redis、JWT 和 MinIO 等配置。脚本不再交互提问，也不生成或读取 `config/backend.env`。

```bash
cd /opt/ai-stock
chmod 700 setup-env.sh
vim setup-env.sh
bash setup-env.sh check
bash setup-env.sh
```

配置区每行 `default_env` 的第二个参数就是默认值。外部注入的同名环境变量优先，显式注入的空值也会保留。密码、密钥使用 Bash 单引号字符串；含单引号时按 Bash 语法转义。填写真实密钥的脚本属于私有配置，不应提交到代码仓库。

必须填写数据库地址、账号和密码，JWT 固定密钥，以及 MinIO 的地址、Access Key、Secret Key 和存储桶。Redis 无密码时保留空字符串；`MINIO_PUBLIC_ENDPOINT` 留空时使用 `MINIO_ENDPOINT`。MinIO 填写对象接口地址（当前端口 9000），其最终下载地址需要模型服务和浏览器均能访问。AI 模型及供应商 API Key 由后台用户配置维护。

脚本默认在前台运行，`run` 参数可省略。普通服务器上需要后台运行时：

```bash
nohup bash setup-env.sh > backend-console.log 2>&1 &
tail -f backend-console.log
```

应用日志写入 `RUOYI_LOG_PATH`，默认是脚本同目录的 `logs/`；常规上传目录默认为同目录的 `uploads/`。修改脚本或容器环境变量后重启服务即可生效，无需重新打包 JAR。重启前需先停止已有后端进程。

## 自行构建 Docker 镜像

已提供完整的 [Dockerfile、构建脚本和 Ubuntu 运行说明](docker/README.md)。可直接使用已构建的 JAR，在服务器执行构建，再通过 `docker run --env-file` 注入配置。

提供的 Dockerfile 自带 Java 8，直接启动 JAR，不读取 `setup-env.sh`。运行配置由 `docker run --env-file` 传入，使用配套的 `docker/backend.docker.env.example` 模板；该文件的值不加引号，不写 `export`，不执行 `source`。

Ubuntu 运行示例采用宿主机网络，同机 MySQL、Redis 可填写 `127.0.0.1`，无需 `-p` 映射端口。监听地址默认 `0.0.0.0`，端口默认 `18080`；上传和日志目录挂载到持久化卷。完整命令见上面的 Docker 运行说明，标准输出可通过 `docker logs` 查看。

脚本不安装数据库、不导入业务表；这些依赖由部署环境准备。现有 `backend.env.example` 可作为环境变量清单参考，脚本不会自动读取它。前台 Nuxt 的 `NUXT_PUBLIC_API_BASE_URL` 和后台管理端的请求转发需要分别配置。
