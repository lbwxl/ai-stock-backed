# 文件分析任务与人工维护

前台文件分析以记录表格展示；“新增分析任务”弹窗先上传原文件，再提交文件 URL、文件处理要求和内容分析要求。后端持久化任务后异步调用当前登录用户配置的默认模型，不提取文字、不做 OCR、不转换文件。

## 当前任务流程

| 状态 | 值 | 行为 |
| --- | --- | --- |
| 未开始 | `NOT_STARTED` | 已保存，等待后台工作线程处理 |
| 分析中 | `PROCESSING` | 自动分析中，或模型不支持文件而等待后台人工维护 |
| 分析成功 | `SUCCEEDED` | 已保存模型结果或人工结果，前台可点击“查看” |
| 分析失败 | `FAILED` | 保存模型认证、网络、文件访问等实际错误及失败原因 |

`processingMode=AUTO` 表示自动处理；`MANUAL` 表示人工维护。用户模型能够直接读取文件 URL 时保存结果；模型请求文件处理工具，或接口拒绝附件且模型无法完成读取时，记录保持 `PROCESSING/MANUAL`，本流程不实际调用第三方服务，也不会自动重复执行人工等待任务。网络、认证等错误不会冒充“不支持文件”。系统提示词仍在后端，前台只提交业务要求。

后台“AI 管理 → 文件分析记录”可按文件名称、提交用户和状态查询，查看或下载原文件。维护时只允许填写分析结果；文件地址、文件处理要求、内容分析要求、状态及失败原因均只读。保存非空结果后由系统自动标记为 `SUCCEEDED/MANUAL` 并清除原失败原因，用户即可在前台查看；维护不会替换文件或重新发起模型分析。后台保存使用版本号校验：打开维护页后自动分析导致的版本变化不会阻挡人工保存；其他人工维护已更新结果时仍返回冲突，迟到的自动分析不会覆盖人工修改。

### 数据库与权限

部署前执行后端 `sql/file_analysis_tasks_20261022.sql`，新增 `file_analysis_task` 表及后台菜单；脚本可重复执行，不修改现有业务数据。本机已执行。其他环境需自行执行，并为维护角色授权：

- `webui:fileAnalysis:list`：管理列表。
- `webui:fileAnalysis:query`：查看记录、下载记录关联的原文件。
- `webui:fileAnalysis:edit`：维护分析结果（通常同时授予查询权限）。

超级管理员刷新页面或重新登录即可加载新菜单。普通前台用户只能查询自己的记录，不能通过请求参数指定其他用户或访问后台维护接口。

### 任务接口

所有接口均携带业务 JWT：

- `POST /webui/file-analysis/tasks`：JSON 字段 `fileUrl`、`processingRequirement`、`analysisRequirement`，均必填；返回若依 `{code:200,data:{id,status:"NOT_STARTED",...}}`。不等待模型完成，同一上传文件只创建一条任务。
- `GET /webui/file-analysis/tasks?pageNum=1&pageSize=10&status=&fileName=`：当前用户分页记录，返回 `{code:200,rows:[],total:0}`。列表不返回完整分析正文及文件签名地址。
- `GET /webui/file-analysis/tasks/{id}`：当前用户记录详情，返回 `{code:200,data:{...}}`，含两项要求、`analysisText`、已发布的 `resultFiles`、状态、处理方式、模型名称、失败原因、时间及新签发的完整 `fileUrl`。
- `GET /webui/file-analysis/records/list`：后台分页查询，可加 `userName`、`userId`、文件名和状态筛选。
- `GET /webui/file-analysis/records/{id}`：后台查看详情。
- `GET /webui/file-analysis/records/{id}/download`：后台下载该记录归属用户的原文件，使用查询权限鉴权，不受分析状态限制；返回原始字节、附件下载头及 UTF-8 编码的 `Download-Filename` 响应头。后台列表和详情弹窗均有“下载文件”按钮。文件由后端从存储读取，不依赖旧签名 URL，也不把业务 JWT 发给 MinIO；文件不存在或无权限时返回错误，不生成下载文件。
- `PUT /webui/file-analysis/records`：后台上传文件模式提交 `id`、读取时的 `version` 和非空 `resultFileIds`，详见文末“后台上传分析结果文件”。旧客户端提交非空 `analysisText`（最多 1048576 字符）仍兼容。保存后系统自动更新状态为分析成功，记录维护人和完成时间；文件、归属用户、两项要求及开始时间保持不变。额外提交文件地址、要求、状态等字段返回 HTTP 400；自动处理导致的旧版本可保存；其他人工维护已更新结果或提交未来版本时返回 HTTP 409，页面显示具体原因并保留草稿。原文件不可用时仍可维护分析结果。原上传替换文件接口已移除。

关联记录的原文件会长期保留，不受临时上传默认 24 小时过期清理影响；临时删除接口会返回 `409 FILE_IN_USE`。完整访问链接仍有签名有效期，每次查看详情会重新签发。文件在外部被移除或存储暂时不可用时，记录和分析结果仍可查看，详情通过 `fileAvailable=false`、`fileErrorMessage` 表示文件异常。MinIO 生命周期策略应避免按上传天数删除已关联记录的文件。

后台默认每 3 秒分发，最多 2 个模型分析同时进行。服务重启会恢复本实例未完成的自动任务；超过 10 分钟仍未完成的自动任务会重新排队。人工等待记录不会被重新排队。前台每 10 秒刷新当前页面上的未完成任务。

### 验证与日志

```bash
node tests/verify-file-analysis-tasks.mjs --live
```

使用真实本地后端和 MinIO，独立的 HTTP 模型桩模拟自动成功、认证失败、请求工具转人工，以及只允许维护分析结果、拒绝修改原始要求或文件、人工结果与自动结果并发冲突；创建临时用户、配置和文件，结束后仅清理本次测试数据。不会修改现有用户默认模型。安全摘要保存在 `.local/file-analysis-task-test-result.json`。

日志在 `.local/logs/file-analysis.log`；可用 `taskId` 或 `requestId` 查找 `task_created`、`task_started`、`task_waiting_manual`、`task_finished`、`task_maintained`。日志不记录提示词正文、分析全文、密钥或完整签名链接。

## 上传、存储和兼容同步接口

下面保留上传、存储及旧同步分析接口的说明。新前台使用上述任务接口；旧 `POST /webui/file-analysis` 为兼容接口，仍可走原有第三方工具适配流程，不生成任务记录。

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

未关联记录的过期文件访问时会被删除。成功上传还会至多每分钟尝试清理一次当前用户的过期对象，单次最多扫描 1000 项；大量文件或长期无访问时，应对专用前缀设置 MinIO 生命周期策略。删除指定本次上传的版本，避免开启版本控制时只留下删除标记而保留原文件版本。

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


## 后台上传分析结果文件（2026-10-30）

先执行增量迁移 `sql/file_analysis_result_files_20261030.sql`，再重新构建并启动后端。迁移仅新增结果附件表，保留历史任务和文字结果。

- `POST /webui/file-analysis/records/{id}/result-files`：需 `webui:fileAnalysis:edit`，使用 multipart 的多个 `files` 字段上传，可混合 Word、PDF、图片等文件；每批 1～20 个，单文件上限沿用存储配置（默认 10MB），请求总大小沿用 Spring multipart 配置（默认 20MB）。上传返回 `data: [{id,fileName,mimeType,fileSize}]`，此时为未发布草稿。
- `PUT /webui/file-analysis/records`：提交 `{id,version,resultFileIds:["附件UUID",...]}`，保留 1～20 个附件；只接受当前记录的已发布附件或当前维护人上传的草稿。保存时保留文件、原子更新发布列表及任务状态为 `SUCCEEDED/MANUAL`；前台随即可查看下载列表。未选择的旧附件退出发布列表，不能继续通过结果下载接口访问。版本冲突返回 409 并保留前端草稿。此模式不同时接受 `analysisText`，旧客户端文字维护接口保留兼容。
- `GET /webui/file-analysis/tasks/{id}`：当前用户的详情新增 `resultFiles` 元数据列表；未保存的上传不出现在列表中。历史文字/自动分析结果仍由 `analysisText` 返回。
- `GET /webui/file-analysis/tasks/{id}/result-files/{fileId}/download`：用户下载自己的已发布结果，其他用户、其他任务或未发布文件返回 404。
- `GET /webui/file-analysis/records/{id}/result-files/{fileId}/download`：管理端下载，需 `webui:fileAnalysis:query`。

所有结果下载均使用业务 JWT（`Authorization: Bearer ...`），返回附件字节、原文件名及 `application/octet-stream`，带 `Content-Disposition: attachment`、`Download-Filename`、`nosniff` 和私有缓存头。前端不拼接 MinIO 地址，不把 token 放 URL，不内联执行上传内容。

附件保存在上传维护人的私有存储空间，并绑定任务，前台仅通过任务归属检查后下载。已发布文件长期保留，草稿未保存则沿用临时上传有效期。上传失败批次回滚新建元数据并清理已传临时文件。附件发布和下载不调用模型，也不依赖模型公网文件入口或原始输入文件是否可用。后台取消编辑不会改动已发布结果，移除附件需保存后生效。

针对该功能的回归测试包括 `FileAnalysisResultFilesTest`、`FileAnalysisTaskServiceTest`，管理前端 `node --test tests/file-analysis-results.test.mjs`，前台 `npm run test:file`。

真实本地 MySQL/MinIO 联调可执行 `node tests/verify-file-analysis-results.mjs --live`，需通过环境提供 `MYSQL_PWD`、可维护记录和新增测试用户的 `TEST_USERNAME`/`TEST_PASSWORD`（不修改已有用户）；数据库账号默认 `ruoyi_local`，可由 `TEST_DB_USER` 指定。脚本创建隔离用户和人工任务，不调用真实模型，结束后清理测试账号、记录及上传对象。
