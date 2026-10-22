# 当前用户 AI 分析接口

前端提交业务场景和数据，后端负责系统提示词、业务模板、输出结构、生成参数，以及登录用户的模型、服务地址和密钥。每次请求按认证用户重新读取配置，无需数据库迁移。普通登录用户无需配置管理权限。

## 请求

`POST /webui/ai/chat/completions`

请求头：`Authorization: Bearer <登录 JWT>`、`Content-Type: application/json`。

此接口接受业务参数，响应继续采用 Chat Completions 的 JSON / SSE 格式。旧版直接提交 `messages` 的客户端需要同时更新。

```json
{
  "scene": "feedback",
  "query": "请分析某公司的现金流",
  "language": "zh",
  "questionCount": 1,
  "stream": true
}
```

| 参数 | 说明 |
| --- | --- |
| `scene` | 必填：`feedback`、`search_queries`、`process_results`、`report` |
| `query` | 必填：原始问题或当前搜索词，不含业务提示词模板 |
| `language` | `zh`（默认）、`en`、`nl`；兼容原语言名称 |
| `feedback` | 可选：`[{"question":"关注什么？","answer":"现金流"}]`；由后端组合问题与回答 |
| `stream` | 布尔值，默认 `false`；前端研究流程使用 `true` |

各场景的数据：

| 场景 | 业务参数 | 返回内容 |
| --- | --- | --- |
| `feedback` | `questionCount`：1–5，默认 3；可选 `images` Base64 图片数组 | `{"questions":["补充问题"]}` |
| `search_queries` | `queryCount`：1–10，默认 3；可选 `searchLanguage`、`learnings` 字符串数组、`researchGoal`、`followUpQuestions` 字符串数组 | `{"queries":[{"query":"搜索词","researchGoal":"研究目标"}]}` |
| `process_results` | 非空 `results:[{"url":"来源网址","content":"搜索内容"}]`；`learningCount`：1–20，默认 5；`followUpCount`：0–10，默认 3 | `{"learnings":[{"url":"来源网址","learning":"发现"}],"followUpQuestions":["深入问题"]}` |
| `report` | 非空 `learnings:[{"url":"来源网址","learning":"发现"}]`，顺序与报告引用对应 | Markdown 报告，使用 `[1]` 等编号引用 |

图片仅用于 `feedback`，最多 20 张，格式为 PNG、JPEG、WEBP、GIF 的 `data:image/...;base64,...`。实际多模态能力取决于模型。

服务不接受客户端提交 `messages`、`system`、`prompt`、`model`、API Key、第三方地址、输出 schema 或生成参数；不支持的字段直接返回 `400`。

## 提示词维护

提示词位于 `ruoyi-webui/src/main/resources/prompts/ai/`：

- `system.txt`：研究角色与通用规则，日期由后端 UTC 时钟填入。
- `feedback.txt`：生成补充问题及 JSON 输出规则。
- `search-queries.txt`：搜索词、历史发现和递归研究方向。
- `process-results.txt`：从搜索结果提取发现及深入问题。
- `report.txt`：Markdown 报告和编号引用规则。

`AiPromptService` 校验参数并组合原始问题、补充回答、研究方向、来源内容和报告引用。模板替换只处理模板本身，不对用户文本进行二次占位符替换。前端保留 Zod 结构用于解析流式结果，以及原始数据的上下文长度裁剪，不再生成模型指令。

修改模板后重新构建并重启后端。前端没有模板副本，不需要跟着修改业务指令。

## 响应与异常

- 非流式返回 Chat Completions JSON；流式返回 `text/event-stream`，保留文本、推理内容和 `[DONE]`，不套若依 `code/data` 包装。
- `400`：业务参数不合法、场景不支持、包含客户端指令字段或用户配置不完整。
- `429`：第三方限流或额度不足。
- `502`：第三方认证/调用失败、地址重定向或无法连接；第三方 `401/403` 不代表业务登录失效。
- `504`：第三方响应超时。
- SSE 开始后连接中断或超时，通过 SSE 中的 `error` 对象报告失败。
- 错误响应为 `{"error":{"message":"提示信息","type":"ai_service_error"},"msg":"提示信息"}`，不转发第三方错误正文。
- 业务登录失效沿用若依处理。前端识别 HTTP `401` 或 HTTP `200` 下 `code: 401`，清除会话并返回登录页。

`GET /webui/config/current` 只返回 AI 服务与模型展示信息，不下发 AI 密钥和第三方地址；搜索配置保持原流程。

## 配置与验证

后台继续维护每个账号的服务、模型、密钥。服务地址可以是 API 基础路径或完整 `/chat/completions` 地址；已知服务可留空使用默认地址，自定义服务必须填写。DeepSeek 应填写 `https://api.deepseek.com/v1`，不会携带密钥跟随 HTTP 重定向。Ollama 可不填密钥，默认地址指向后端机器。

可选后端超时配置（毫秒）：

```yaml
webui:
  ai:
    connect-timeout: 10000
    read-timeout: 120000
```

前端 `NUXT_PUBLIC_API_BASE_URL` 指向此后端。需要同步发布前后端，避免旧客户端提交 `messages`。经过 Nginx 等代理时，需允许长时间流式响应并关闭此接口的响应缓冲。

后端验证：`mvn -pl ruoyi-admin -am test`。使用本地模拟供应商，覆盖四个场景的提示词组合、输入校验、用户隔离、流式输出、图片和异常处理。

前端验证（`ai-stock-analysis-tool`）：`npm run test:ai`、`npm run test:auth`、`npm run build`。验证四个场景只发送业务数据，并保留各供应商的流式解析。

## 文件分析

文件先通过 `POST /webui/file-analysis/files` 上传，再向 `POST /webui/file-analysis` 发送 JSON 文件 URL 和业务要求。由当前用户配置的模型判断直接分析或调用预留的外部工具，后端不提取文件内容，向模型提供临时签名 URL，由模型服务直接下载。接口、工具扩展和限制见 [FILE_ANALYSIS_API.md](FILE_ANALYSIS_API.md)。
