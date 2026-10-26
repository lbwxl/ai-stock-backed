# Ubuntu Docker 部署

本目录将现成的 `ruoyi-admin.jar` 打入 Java 8 镜像，服务器只需 Docker，无需安装 Java 或 Maven。MySQL、Redis、MinIO 和已导入业务表的数据库需提前准备好，镜像不包含这些服务或本机数据。构建时需要能访问镜像仓库。

## 构建和启动

下载的 Docker 构建包已包含 JAR，在服务器上解压后执行：

```bash
cd ai-stock-backend-docker
sha256sum -c ruoyi-admin.jar.sha256
bash build-docker.sh

cp backend.docker.env.example backend.docker.env
chmod 600 backend.docker.env
nano backend.docker.env
```

填写实际的 MySQL、Redis、JWT 和 MinIO 配置。此文件供 `docker run --env-file` 读取，值不要加引号，不写 `export`，也不执行 `source`。密码中的 `$` 等字符直接填写。容器直接启动 Java，不读取 `setup-env.sh` 或原来的 Bash env 文件。模型和 API Key 仍由后台用户配置维护。

以下命令适用于 **Ubuntu 上的 Docker Engine**。容器使用宿主机网络，因此 MySQL、Redis 如果就在同一台服务器，可使用 `127.0.0.1`；其他服务器上的服务填实际地址。若数据库、Redis 也在容器内，需先将相应端口发布到宿主机，或自行改为共同的 Docker 网络和服务名。启动前先停止占用 `18080` 的旧后端；容器名已存在时，先按下文重建容器。

```bash
docker run -d \
  --name ai-stock-backend \
  --restart unless-stopped \
  --network host \
  --env-file ./backend.docker.env \
  --mount source=ai-stock-data,target=/data \
  --log-driver json-file \
  --log-opt max-size=10m \
  --log-opt max-file=3 \
  ai-stock-backend:latest

docker logs --tail 100 -f ai-stock-backend
```

日志出现 `Started RuoYiApplication` 后可访问 `http://服务器地址:18080`。如需从外部访问，请按部署网络开放此端口，或由 Nginx 转发。宿主机网络模式不需要 `-p` 端口映射；默认监听地址为 `0.0.0.0`。仅供同机 Nginx 访问时可将 `SERVER_ADDRESS` 设置为 `127.0.0.1`。

## 数据和日志

`ai-stock-data` 是持久化命名卷，保存普通上传文件、应用日志及旧格式文件数据；新文件原件仍存储在 MinIO。删除或重建容器时保留此卷，不执行 `docker volume rm ai-stock-data`。数据库和 MinIO 原有数据保持在各自的服务中。

容器以 UID/GID `10001:10001` 运行。使用上面的命名卷会自动初始化目录权限；如果改为绑定服务器目录，需先给该用户授予相应目录写权限。

```bash
# 控制台和文件分析阶段日志
docker logs --tail 200 -f ai-stock-backend
docker exec ai-stock-backend tail -n 100 -f /data/logs/file-analysis.log

# 停止或启动已创建的容器
docker stop ai-stock-backend
docker start ai-stock-backend
```

MinIO 下载地址必须能被模型服务和用户浏览器访问，`MINIO_PUBLIC_ENDPOINT` 留空时使用 `MINIO_ENDPOINT`。

## 修改配置或升级 JAR

修改 `backend.docker.env` 后需重建容器，`docker restart` 不会重新读取该文件。更新 JAR 时先替换构建目录中的 JAR 并重新执行 `bash build-docker.sh`，再重建容器。

```bash
docker stop ai-stock-backend
docker rm ai-stock-backend
# 重新执行上面的 docker run 命令，继续使用 ai-stock-data 卷。
```

在源码仓库中，也可执行 `bash deploy/docker/build-docker.sh /绝对路径/ruoyi-admin.jar ai-stock-backend:版本号`。脚本只把 Dockerfile、JAR 和忽略文件送入构建上下文；密码配置、日志、数据库目录不会进入镜像。直接在目标 Ubuntu 服务器构建，可避免本机与服务器 CPU 架构不同导致无法运行。

参考：[Temurin 官方镜像](https://hub.docker.com/_/eclipse-temurin)、[Docker 宿主机网络](https://docs.docker.com/engine/network/drivers/host/)、[docker run 环境变量文件](https://docs.docker.com/reference/cli/docker/container/run/#env)。
