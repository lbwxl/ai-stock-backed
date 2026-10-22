# 第三方文件分析隔离测试

本测试模拟其他部门提供文件分析服务的对接方式，不向生产 Spring 容器注册工具，不修改用户配置，也不改动前端。只有真实主模型返回 `analyze_uploaded_file` 工具调用时，测试工具才请求第三方服务。

## 调用过程

1. 测试生成并上传一份 PDF，页面含随机六位 `ACCESS CODE`；数字不会写入分析提示词。
2. 使用本地数据库中用户 1 当前配置的模型，调用实际文件分析控制器和服务。
3. 主模型接口拒绝附件时，把拒绝信息和文件元信息交给同一模型，由模型决定是否调用工具。
4. 工具向仅监听 `127.0.0.1` 的测试部门服务发送 JSON：`requestId`、完整签名 `fileUrl`、`fileName`、`mimeType`、`processingRequirement`、`analysisRequirement` 和模型生成的 `task`。
5. 测试部门服务把原文件 URL 和要求发送到 `https://api1.aisz.mom/v1/responses`，使用用户确认的 `gpt-6-astra`、`reasoning.effort=high`、`store=false`。图片使用 `input_image.image_url`，其他文件使用 `input_file.file_url`；不提取、转换或编码文件内容。格式参考 [图片输入](https://developers.openai.com/api/docs/guides/images-vision) 和 [文件输入](https://developers.openai.com/api/docs/guides/file-inputs)。实际格式支持仍取决于第三方平台。
6. 第三方结果作为工具消息交回原主模型，由原主模型生成控制器最终返回的 `analysisText`。
7. 测试只有在最终响应为 200、模型确实调用工具、最终文本含 PDF 中的随机数字时才算成功。结束后删除本次生成的文件和包含主模型配置的临时文件，并关闭测试部门服务。

控制器通过 MockMvc 调用。主模型、第三方平台、签名文件下载和公网隧道在真实测试中使用实际网络服务，未通过浏览器执行。

## 2026-10-22 验证结果

- 本地第三方服务协议模拟：8 项测试通过，涵盖 URL 和双重要求传递、图片格式、JSON/SSE 解析、HTTP 正常返回、鉴权及上游失败。此类测试使用固定上游输出，不代表真实模型读取了文件。
- 后端回归：45 项，44 项通过，真实调用测试默认跳过 1 项。已有回归覆盖“附件拒绝 → 模型调用工具 → 工具结果交回原模型 → 返回最终分析”。
- 真实链路：用户配置的 `deepseek-flash` 首次携带 PDF 请求返回 400；同一模型收到附件拒绝信息后返回 200 并主动调用工具；工具向第三方 `gpt-6-astra` 发起真实请求，第三方返回 503。控制器返回 502 `SIMULATION_THIRD_PARTY_FAILED`，没有返回虚构分析结果。
- 同服务最简单的文本 Responses 请求起初也返回 503 `Service temporarily unavailable`；随后使用 curl 对比不显式使用 HTTP 代理和通过系统 HTTP 代理 `127.0.0.1:7897` 的请求，两者均返回 200 且完成响应。无需据此给脚本强制加代理；此前的 503 更像上游临时不可用，具体原因需要服务商日志确认。
- 后续一次完整文件请求中，主模型再次调用工具，第三方返回 400，尚未取得足够诊断信息判断是参数兼容还是文件下载问题。之后 Cloudflare 临时隧道出现 530 和超时，签名文件就绪检查失败，该次没有发起模型调用。因此本次未通过真实文件内容校验；不能声称 `gpt-6-astra` 已成功下载或分析 PDF。每次测试 PDF 均已删除。

最近一次真实结果和脱敏事件分别保存在被 Git 忽略的 `.local/file-analysis-simulation-result.json`、`.local/file-analysis-simulation-trace.json`。每次真实复测会覆盖这两个文件。

## 本地协议模拟

无需密钥、数据库、隧道，也不调用外部模型：

```sh
node --test tests/third-party-file-service.test.mjs
```

## 真实复测

本地业务服务现已使用 MinIO。测试工具也支持 `--minio`，通过原有私有配置生成 MinIO GET/HEAD 签名 URL，无需启动 Cloudflare/ngrok：

```sh
node tests/run-third-party-file-simulation.mjs --live --minio
```

以上选项只改变隔离测试的文件存储；第三方工具仍只注册在测试对象中，不会启用到生产服务。MinIO 存储和真实主模型直接读图验证见 [FILE_ANALYSIS_API.md](FILE_ANALYSIS_API.md)。第三方代理平台是否支持 PDF URL 仍需该隔离测试另行确认；MinIO 联通不代表第三方模型接口已验证成功。

以下不加 `--minio` 的命令保留用于本地文件及隧道方案：

前提：本地数据库、后端和文件隧道已启动，当前用户模型配置有效，第三方平台恢复可用。专用第三方密钥存放在被 Git 忽略的 `.local/file-analysis-third-party.key`；不要写进源码。

```sh
./start-local.sh
./start-file-tunnel.sh cloudflare
node tests/run-third-party-file-simulation.mjs --live
```

`--live` 会调用真实外部 API，可能产生模型费用。运行器先等待文件隧道可访问，再启动测试部门服务并执行指定 Maven 测试。测试还会等待实际签名文件 HEAD 检查成功，避免仅凭隧道状态就调用模型。默认 Maven 测试不会执行真实调用。公网入口只提供签名文件下载，测试部门服务本身不会被隧道公开。

相关实现：

- `tests/fixtures/third-party-file-service.mjs`：测试部门服务及 Responses 适配。
- `tests/mock-file-analysis-service.mjs`：测试部门服务进程入口。
- `tests/run-third-party-file-simulation.mjs`：真实调用的显式启动器。
- `ruoyi-admin/src/test/java/com/ruoyi/web/controller/webui/ThirdPartyFileAnalysisSimulationTest.java`：实际主模型工具调用与最终文件内容校验。

生产环境仍使用原有工具扩展口。将来接入其他部门时，可实现 `FileAnalysisTool`，在 `analyze(context, task)` 中调用部门 HTTP 接口并返回分析文本，再按正式配置选择该实现。
