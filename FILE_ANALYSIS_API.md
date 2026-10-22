# 原文件分析与模型工具入口

已接通前端文件分析页。后端不提取文字、不做 OCR、不转换文件，也不按文件类型或模型名称决定分析流程。所有文件通过临时授权 URL 交给登录用户配置的模型，由模型决定直接分析还是调用工具。

## 调用流程

1. 浏览器先调用上传接口保存原文件，取得完整的临时签名 `fileUrl` 和 `fileId`。开始分析时，通过 JSON 提交此 URL 和两项业务要求，携带业务登录 JWT。
2. 后端验证文件归属和有效期，读取元数据并生成临时签名 URL；从登录身份读取用户模型配置，组装服务端提示词、URL 附件及工具定义。模型请求不加载或内嵌原文件。发送模型请求前用 HEAD 校验临时链接的 HTTP 状态、Content-Type 和长度，不读取文件正文；链接返回隧道错误页、重定向、类型不符或长度不符时，返回 `503 FILE_URL_UNREACHABLE`，不会误转入外部工具。若模型拒绝附件，会再次检查链接，区分网络故障与模型能力限制。
3. 模型直接返回内容时，返回分析结果。
4. 模型返回 `analyze_uploaded_file` 工具调用时，后端才将同一份文件的临时授权 URL 交给预留的 `FileAnalysisTool`。工具结果交回同一个用户配置的模型，由模型形成最终结果。
5. 如果携带 URL 附件的请求被上游以 HTTP 400/415/422 拒绝，后端仅把请求失败事实及文件元信息再次提交给同一个模型，仍由模型判断是否调用工具。不会自动执行第三方服务。没有原文件内容也没有工具结果时，不接受模型编造的“分析结果”。

当前第三方平台尚未确定，默认工具会返回 `503 FILE_TOOL_NOT_CONFIGURED`，明确提示服务未配置。支持 URL 附件并直接完成分析的模型不依赖这个工具。

## HTTP 接口

上传、分析、私有下载与删除接口使用 `Authorization: Bearer <业务 JWT>`，普通登录用户可使用自己的文件，不需要配置管理权限。

### 先上传

`POST /webui/file-analysis/files`

请求体为 `multipart/form-data`，只有一个必填 `file` 字段，非空，默认最大 10 MiB。由浏览器生成 Content-Type 和 boundary。上传不调用任何模型。

返回 HTTP 200：

```json
{
  "fileUrl": "https://files.example.com/ai-file/file-analysis/1/12345678-1234-1234-1234-123456789abc?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=...",
  "fileId": "12345678-1234-1234-1234-123456789abc",
  "fileName": "example.pdf",
  "mimeType": "application/pdf",
  "size": 102400,
  "expiresAt": 1792713600000,
  "urlExpiresAt": 1792629000000,
  "requestId": "请求 UUID"
}
```

`expiresAt` 为文件过期时间的 Unix 毫秒数，默认保存 24 小时。`fileUrl` 为完整的 MinIO 临时签名 GET 地址，可直接访问，不需业务 JWT；默认约 30 分钟有效，`urlExpiresAt` 为链接过期时间。分析请求原样提交 `fileUrl`，后端校验归属后签发新的模型访问链接，因此旧签名过期但文件未过期时仍可再次分析。`fileId` 用于业务后端的私有下载和删除，`requestId` 可检索本次上传日志。

### 再分析

`POST /webui/file-analysis`，`Content-Type: application/json`：

```json
{
  "fileUrl": "https://files.example.com/ai-file/file-analysis/1/12345678-1234-1234-1234-123456789abc?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=...",
  "processingRequirement": "保持原文件，阅读全部内容",
  "analysisRequirement": "分析并总结重点"
}
```

三个字段均必填；处理要求与分析要求各最多 10000 字符。仅接受上述字段，不接受二进制、Base64、用户 ID、模型、密钥或客户端提示词。后端只接受配置中 MinIO 的准确协议、域名/端口、桶和对象前缀，并检查对象路径中的用户 ID。不会抓取任意 HTTP 地址，不能通过 URL 指定服务器文件路径或其他用户的文件；旧相对资源标识继续兼容。

### 下载和删除

- `GET /webui/file-analysis/files/{id}`：当前文件归属用户携带 JWT 才能下载，返回原始字节、附件下载头、`private, no-store`，不进行内容解析。
- `DELETE /webui/file-analysis/files/{id}`：删除当前用户自己的文件，返回 HTTP 204。前端移除/更换文件时尝试删除；失败则由过期机制处理。

### 模型直接读取 URL

后端将私有文件 URL 换成临时授权 URL。MinIO 模式使用 S3v4 预签名 GET 地址，模型直接从 MinIO 下载原文，不经过业务后端或本地隧道。后端检查可达性时另签 HEAD 地址；GET 签名绑定请求方法，不能直接用于 HEAD。

本地磁盘模式使用以下地址：

```text
https://files.example.com/webui/file-analysis/model-files/{userId}/{id}/{expires}/{signature}/{filename}
```

模型服务直接 GET 此 URL 下载原文件，无需业务 JWT。链接使用每文件独立随机密钥的 HMAC-SHA256 签名，限定用户、文件及有效期；默认 30 分钟，且不超过上传文件的有效期。过期、篡改或删除文件后拒绝访问。密钥保存在私有目录，后端重启不会使已有链接失效；支持 HEAD 探测。

只发送 URL：PNG/JPEG/GIF/WEBP 使用 `image_url.url`；其他文件使用 `file.filename` 与 `file.file_data`（值为 URL）。提示词也提供链接，要求自行读取且禁止泄露。主模型与预留工具均不接收内嵌字节。后端只在上传保存、响应下载和旧文件迁移时读写原文件，不提取内容、OCR 或转换。

### MinIO 存储

当前本地运行配置已切换到 MinIO，不需要启动文件隧道。运行时地址和凭据从环境变量注入，配置模板见 [backend.env.example](backend.env.example)：

```yaml
webui:
  file-analysis:
    storage-type: ${FILE_ANALYSIS_STORAGE_TYPE:local}
    model-url-ttl-seconds: 1800
    retention-seconds: 86400
    minio:
      endpoint: ${MINIO_ENDPOINT}
      public-endpoint: ${MINIO_PUBLIC_ENDPOINT:}
      access-key: ${MINIO_ACCESS_KEY}
      secret-key: ${MINIO_SECRET_KEY}
      bucket: ${MINIO_BUCKET:ai-file}
      region: ${MINIO_REGION:us-east-1}
      prefix: ${MINIO_PREFIX:file-analysis/}
```

`public-endpoint` 可省略，默认与 `endpoint` 相同；内网上传和公网下载地址不同的部署需要分别配置。外部模型必须能访问签名时使用的地址，不要在签名完成后替换域名、端口或路径。默认使用路径形式访问桶，兼容用户提供的 S3v4 接口。

本机在 Git 忽略的 `.local/backend.env`（权限 `600`）中设置 `FILE_ANALYSIS_STORAGE_TYPE=minio`、`MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET` 等变量。`./start-local.sh` 将其作为环境变量传给 Java，已导出的环境变量优先于该文件。修改后重启生效。IDEA 或服务器直接启动 JAR 时，需要在运行配置、容器或服务管理器中注入同名变量，不会自动读取这个本地文件。旧 JSON 配置仅保留兼容能力，当前运行配置不再使用它。

原文件对象路径为 `file-analysis/<用户ID>/<随机UUID>`，原文件名、MIME 和有效期存放在对象元数据中；新上传不保留本地原文件或元数据副本。前端接收并提交完整 `fileUrl`，由后端根据登录身份验证对象归属及有效期后生成新签名 URL，避免上传后长时间等待导致链接过期。私有下载和删除仍使用业务接口 `/webui/file-analysis/files/{fileId}`，前端不会向 MinIO 发送业务登录 Token。

切换前的本地文件仍可下载和删除；再次分析时会把原字节及原有效期迁移到 MinIO，确认远端对象有效后才移除本地副本，不需要临时隧道。迁移不进行文件解析或内容提取。

过期文件访问时会被删除。成功上传还会至多每分钟尝试清理一次当前用户的过期对象，单次最多扫描 1000 项；大量文件或长期无访问时，应对专用前缀设置 MinIO 生命周期策略。删除指定本次上传的版本，避免开启版本控制时只留下删除标记而保留原文件版本。

桶应保持私有。签名 URL 在有效期内无需业务 JWT 即可下载；若桶允许匿名读取，移除签名也可能直接访问文件。2026-10-22 实测当前 `ai-file` 桶允许匿名下载，接入没有修改原桶策略。生产环境还应配置模型可访问、证书有效的 HTTPS 文件入口；当前提供的地址为 HTTP。

验证命令（仅 `--model` 会额外调用当前用户模型）：

```bash
node tests/run-minio-verification.mjs --live
node tests/run-minio-verification.mjs --live --model
node tests/verify-minio-upload.mjs --live
```

第一条验证真实 SDK 上传、签名 GET/HEAD、原字节、用户隔离和删除；第二条增加真实模型读取图片像素中的随机数字校验；第三条验证运行中后端的登录、上传、MinIO 对象存在、私有下载及远端删除。均只清理本次创建的测试文件/会话，不修改其他对象或用户模型配置。结果保存在 Git 忽略的 `.local/minio-live-result.json` 和 `.local/minio-runtime-result.json`。普通 Maven 测试默认跳过外部调用。

供应商必须支持相应附件 URL 协议，公网可达不等于任意模型支持任意文件。协议参考 [DeepSeek 图片 URL](https://api-docs.deepseek.com/guides/vision/) 和 [OpenRouter PDF URL](https://openrouter.ai/docs/guides/overview/multimodal/pdfs)。不兼容供应商需另加协议适配。不支持文件时由模型决定是否调用预留工具，不回退为 Base64 或本地提取。

### 本地磁盘模式的隧道测试（ngrok / Cloudflare）

本机 localhost 地址不能供外部模型下载。先按 [ngrok 官方说明](https://ngrok.com/download/mac-os) 安装并在本机配置账号令牌，再执行：

```bash
./start-local.sh
./start-file-tunnel.sh
```

脚本启动绑定 `127.0.0.1:18081` 的文件网关，再用 ngrok 暴露此端口。网关仅转发格式匹配的 `GET/HEAD /webui/file-analysis/model-files/...`，其他路由全部拒绝。登录、管理、分析及私有下载接口不通过隧道。ngrok 请求检查关闭，网关不记录签名 URL。

守护进程负责文件网关与隧道生命周期，监测进程退出、Cloudflare 临时隧道撤销和公网健康状态，失效后自动重建。确认公网到达本机网关后，将域名原子写入 `.local/file-tunnel-url`，后端每次签发链接时读取，域名改变无需重启。状态在 `.local/file-tunnel-status.json`；恢复期间明确提示稍后重试，原上传不受影响。显式配置 `public-base-url` 优先。隧道关闭或域名改变后，旧域名的链接不再保证可用。

也可使用 [Cloudflare Quick Tunnel](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)，需要安装 `cloudflared`，无需账号和自有域名：

```bash
./stop-file-tunnel.sh
./start-file-tunnel.sh cloudflare
```

两种模式使用同一个文件网关和签名规则，默认参数为 `ngrok`。启动前需要先停止已有的文件隧道。

`./stop-file-tunnel.sh` 停止本项目文件网关和当前隧道（ngrok 或 cloudflared），移除自动域名文件，不停止业务后端。账号令牌仅由本机 ngrok 配置管理，不保存在项目中。

ngrok 免费入口可能给浏览器式 User-Agent 返回 HTML 提示页，文件供应商若使用这种请求方式可能把提示页误判为无效文件。本机实测默认服务端请求返回原 PNG，而浏览器式请求返回 HTML。模型端不能自行添加跳过提示页的请求头时，需要无提示页的域名/隧道。参见 [ngrok 免费计划限制](https://ngrok.com/docs/pricing-limits/free-plan-limits/)。

成功返回 HTTP 200（不套若依 code/data 包装）：

```json
{
  "requestId": "请求 UUID",
  "fileName": "example.pdf",
  "analysisText": "模型最终返回的分析结果",
  "previewFile": null
}
```

初版仅返回分析文字。`previewFile` 保留给将来实际生成文件的扩展，目前始终为 null；不会生成或伪造文件下载地址。MinIO 模式保存远端对象及其元数据；本地磁盘模式默认保存在 `.local/ai-files/<用户ID>/<随机UUID>/`，不在公开上传目录中。重启后仍可在有效期内读取。读取过期文件时删除并返回 410。

错误返回（认证及容器级上传错误可能沿用若依格式）：

```json
{
  "requestId": "请求 UUID",
  "code": "FILE_TOOL_NOT_CONFIGURED",
  "message": "当前模型判断需要外部文件分析能力，但第三方处理服务尚未配置，请联系管理员接入后重试"
}
```

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_FILE / INVALID_FILE_URL / MODEL_NOT_CONFIGURED | 参数、文件或用户配置不完整 |
| 401 或业务 code:401 | 沿用登录鉴权 | 前端清理失效登录并返回登录页 |
| 404 | FILE_NOT_FOUND | 文件不存在或属于其他用户 |
| 403 | INVALID_FILE_SIGNATURE | 下载签名无效 |
| 410 | FILE_EXPIRED / FILE_URL_EXPIRED | 文件或临时链接过期 |
| 413 | FILE_TOO_LARGE | 超过上传限制 |
| 422 | MODEL_CANNOT_READ_FILE | 上游拒绝附件，模型也未调用工具 |
| 429 | MODEL_RATE_LIMITED | 模型限流或额度不足 |
| 502 | MODEL_AUTH_FAILED / MODEL_REQUEST_FAILED / MODEL_REQUEST_REJECTED / MODEL_UNAVAILABLE | 模型请求失败，不清理业务登录 |
| 502 | INVALID_MODEL_RESPONSE / EMPTY_ANALYSIS / ANALYSIS_INCOMPLETE | 模型响应错误、空内容或输出被截断 |
| 502 | INVALID_TOOL_CALL / INVALID_TOOL_RESULT / TOOL_LIMIT_REACHED | 工具协议或执行轮数异常 |
| 502 | FILE_STORAGE_UNAVAILABLE | MinIO 连接、认证、桶权限或读写失败 |
| 503 | FILE_TOOL_NOT_CONFIGURED | 模型要求工具，但工具尚未接入 |
| 503 | FILE_PUBLIC_URL_NOT_CONFIGURED | 本地磁盘模式尚未配置文件公网入口 |
| 503 | FILE_URL_UNREACHABLE | 文件入口不可达、返回错误内容或正在自动恢复；无需重新上传 |
| 504 | ANALYSIS_TIMEOUT | 单次模型请求超时 |

## 接入第三方平台或专用模型

实现 `com.ruoyi.webui.file.FileAnalysisTool` 为 Spring Bean，`getId()` 返回唯一 ID，并通过配置选择该 ID：

```yaml
webui:
  file-analysis:
    storage-path: /absolute/private/ai-files # 默认当前工作目录下 .local/ai-files，不能放在 ruoyi.profile 公开目录内
    retention-seconds: 86400 # 允许 60–604800 秒
    public-base-url: https://files.example.com # 生产文件入口；本地测试留空使用隧道
    model-url-ttl-seconds: 1800 # 允许 60–3600 秒
    # public-base-url-file: /absolute/path/.local/file-tunnel-url
    max-file-size: 10485760
    max-tool-rounds: 3
    tool-handler: unconfigured # 接入后改为实际 Bean 的 getId()，不是模型名称
```

`analyze(FileAnalysisContext context, String task)` 接收：

- 本次认证用户 ID、请求编号、文件名、声明 MIME、大小、临时授权 URL（`context.getFileUrl()`）。
- 用户填写的处理/分析要求及当前模型决定的 `task`。

返回字符串作为工具数据（可用 JSON 字符串），由用户模型继续分析。适配器使用 `context.getFileUrl()` 对接平台或专用模型，并设置请求超时及输出限制；服务地址和凭据放后端配置。不要接受模型传入地址、文件路径或用户身份，也不要向第三方转发业务 JWT。这里没有默认第三方平台，也没有默认切换其他模型。

主模型看到的函数只有 `analyze_uploaded_file(task: string)`，不能指定其他用户的文件。每次只接受一个工具调用；默认最多 3 轮、配置范围 1–5，返回给模型的工具内容最大约 4 Mi 字符。单次模型 JSON 响应最大 4 MiB。保留推理模型工具续接需要的 `reasoning_content`。

Spring 全局上传限制仍需满足需求：`spring.servlet.multipart.max-file-size` 默认 10MB、`max-request-size` 默认 20MB。若提高服务限制，也应同步调整容器和反向代理限制。前端上传等待 2 分钟，分析等待 10 分钟，后端单次模型连接/读取超时沿用 `webui.ai`（默认 10/120 秒）。

提示词在 `ruoyi-webui/src/main/resources/prompts/ai/file-analysis-system.txt` 与 `file-analysis.txt`；前端不拼接模型提示词。图片/PDF 只做浏览器本地预览，其他格式显示文件信息；前端不读取并提取文本。

## 验证

- 后端：`mvn -pl ruoyi-admin -am test`，本地模拟模型覆盖先上传再提交 URL、私有下载和删除、用户归属、重启保留、过期处理、拒绝任意地址、签名防篡改、链接过期、模型服务实际 GET URL、模型请求无 Base64、HEAD 检查错误页/长度、模型请求期间链接失效不调用工具、下载字节不变、直接分析、模型调用工具、同模型续接、附件拒绝后的模型决策、未配置入口、身份隔离、错误和轮数限制。
- 隧道网关：`python3 -m unittest discover -s tests -p test_file_url_gateway.py`，验证允许的 GET/HEAD、原始字节、业务 JWT 不转发、业务和私有路由拒绝。
- 前端：`npm run test:file`、`npm run test:ai`、`npm run test:auth`、`npm run build`。

## 本地实测结果

2026-10-22，使用 Cloudflare 临时隧道与当前用户配置的 DeepSeek Flash 完成图片 URL 分析：仅图片像素包含校验数字，模型请求只发送 URL，模型服务产生一次实际文件 GET，并正确读出数字。浏览器式及常见服务端 User-Agent 均下载原 PNG；错误签名拒绝，登录、管理、分析及私有文件路由返回 404。测试上传与独立登录会话已清理。此结果验证图片 URL 链路，其他文件格式仍取决于模型与供应商协议。

当前环境采用 IPv4 + HTTP/2 连接 Cloudflare，以避免先前 IPv6 隧道连接失效；启动脚本会检查公网请求实际抵达网关后才登记域名。临时域名仅用于测试，不作为生产固定域名。

## 查看文件分析处理日志

脚本启动的本地日志目录为 `.local/logs/`，服务器可通过 `RUOYI_LOG_PATH` 指定目录。

- `file-analysis.log`：文件上传、签名链接准备、HEAD 检查、模型请求及状态、附件拒绝后重试、工具调用、分析完成/失败等阶段日志，按天轮转，保留 30 天。
- `backend-console.log`：完整启动及控制台日志。
- `sys-info.log`、`sys-error.log`：系统信息和错误日志。

```bash
tail -f .local/logs/file-analysis.log
tail -f .local/logs/backend-console.log
rg -F '具体请求编号' .local/logs/file-analysis.log
```

上传与分析响应包含 `requestId`；同一次请求的各阶段使用相同编号。正常响应同时返回 `X-Request-Id` 头。日志只记录用户 ID、文件 ID、MIME/大小、模型名、HTTP 状态、错误码、耗时和结果长度，不记录密钥、业务 JWT、完整签名 URL、文件正文、完整提示词或模型分析正文。
