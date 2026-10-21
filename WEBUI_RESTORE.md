# 二开接口还原记录

## 结果

已在本地若依 3.9.0 源码中还原 29 个 HTTP 方法与路径组合，重新构建并启动在 `http://127.0.0.1:18080`。原前端继续通过 `http://localhost:1024/dev-api` 访问。本地 MySQL `ry-vue` 和 Redis 沿用当前项目实例。

## 制品与分析证据

- 用户提供的制品：`/Users/wxl/Downloads/ruoyi-admin.jar`。
- SHA-256：`da7c5d28b33f8999bb5590d388f346b65c3a11662855e91fcf3242ef501b8323`。
- REA 6.7.0：通过官方 `rea-agents` 包建立临时 MCP stdio 连接，实际执行了 `binary_session`、`open_binary`、`inspect_artifact`、`export_evidence_bundle`、`close_binary`。没有修改 Codex 全局 MCP 配置。
- REA 制品检查 Evidence：`ev_c3b0184cb8b29a6e02dfa9130efedc619303c91c025764eb3b13c12091630212`。
- REA ZIP 目录 Evidence：`ev_f9ea0a85011714cd92883a4fdf8b8954756d0d48b9c7b80988bbd83a8db2f08c`。
- 完整 REA 证据保存在 `.local/reverse/rea-evidence-bundle.json`。包结构证据核对了 238 个文件及 30 个目录；这是 ZIP 中央目录检查，不等同于 Java 方法语义或运行验证。
- Java 源码使用 CFR 0.152 从上述制品反编译，解析时提供原 JAR 内全部 126 个依赖包。提取、原始反编译结果及标准模块比较保存在忽略提交的 `.local/reverse/` 中。
- 原始 JAR 未修改。原包中的数据库地址、凭据和部署配置没有迁入本地后端。

REA 工具来源：[morluto/rea](https://github.com/morluto/rea)。本次使用了包内 `reverse-engineer-anything` 技能；Java 方法实现依据是 CFR 反编译结果，运行结果依据是下述本地集成测试。

## 还原清单

| 范围 | 源码与行为 |
| --- | --- |
| `ruoyi-webui` | 4 个实体、5 个 VO、4 个 Mapper、4 个服务接口及4个实现，共 21 个 Java 类；4 份 Mapper XML |
| `ruoyi-admin` | `AiModelController`、`AiServiceController`、`SearchServiceController`、`UserWebuiConfigController`、`WebUIController` 和 `AuthDto` |
| 用户下拉 | `SysUserController` → `ISysUserService.list()` → `SysUserMapper.selectList()`，仅选择用户 ID 和名称 |
| 身份校验 | 恢复原包对 `/webui/auth/in` 的匿名入口配置；该接口内部仍调用 Spring AuthenticationManager 校验密码和用户状态 |
| 构建接入 | 父 POM 增加 `ruoyi-webui` 模块和版本管理，`ruoyi-admin` 依赖该源码模块 |

| HTTP 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/webui/{service,model,search,config}/list` | 分页列表及筛选，共4个 |
| GET | `/webui/{service,model,search,config}/{id}` | 详情，共4个 |
| POST | `/webui/{service,model,search,config}` | 新增，共4个 |
| PUT | `/webui/{service,model,search,config}` | 修改，共4个 |
| DELETE | `/webui/{service,model,search,config}/{ids}` | 删除，共4个，参数支持 ID 数组 |
| POST | `/webui/{service,model,search,config}/export` | Excel 导出，共4个 |
| GET | `/webui/service/select/list` | AI 服务下拉 |
| GET | `/webui/search/select/list` | 搜索服务下拉 |
| GET | `/webui/model/select/list/{id}` | 指定 AI 服务的模型下拉 |
| GET | `/system/user/select/list` | 用户下拉 |
| POST | `/webui/auth/in` | 用户名、密码校验及该用户业务配置返回 |

`/webui/auth/in` 保留原返回结构：`data.userId`、`data.username`、`data.aiService`、`data.searchService`；它不签发管理后台 JWT。错误用户名或密码、长度错误和空输入按原包返回业务错误码 400。管理后台仍通过验证码加 `/login` 获取 JWT。

## 必要适配与边界

1. 原 `AiModelMapper.selectAiModelList` 和 `UserWebuiConfigMapper.selectUserWebuiConfigList` 未声明结果类型；已补齐对应 VO 的 `resultType`，并将输入 `parameterType` 对齐实体。
2. 模型下拉添加明确的 `@Param("aiServiceId")`，SQL 按同名参数过滤，消除对单参数 MyBatis 隐式绑定的依赖。
3. Mapper 中的硬编码 `` `ry-vue`. `` 前缀改为使用当前数据源数据库，查询字段及联表关系保持一致。
4. 清理反编译警告、未使用的认证局部变量和控制器调试 `main` 方法；保留实体字段、VO JSON 名称、路由、业务流程和权限表达式。
5. 搜索服务控制器按原 JAR 及现有前端沿用 `webui:service:*` 权限名。当前 SQL 的搜索菜单权限名为 `webui:search:list`；如果后续配置非管理员角色，需要同时核对前端按钮、菜单及后端权限名。本次未改变这项原有约定。
6. 与二开接口无关的标准模块差异未迁移，包括原包的头像命名/删除策略、密码和头像按用户 ID 更新的内部合约、通用 helper、Excel 合计格式及 JSON 白名单改动。

反编译可以还原本次接口所需的实现，不能恢复原作者的注释、精确排版或所有局部变量名；本次交付的是可编译维护的 Java 源码，没有把原二进制作为隐藏依赖接入。

## 验证

`mvn -B -ntp -s local-maven-settings.xml -DskipTests package`：8 个模块构建成功。仓库没有原作者的测试用例，本次实际接口验证由 `tests/verify-webui.mjs` 完成。

集成脚本分别通过后端直连和前端代理完整执行通过，覆盖：

- 本轮验证码的 Redis 读取、后台登录和用户信息。
- 匿名访问四组管理列表及详情被拒绝。
- WebUI 登录成功、空输入、长度错误、错误密码以及登录配置嵌套 VO 联表字段。
- 四组新增、详情、修改、删除、筛选、分页及联表字段。
- AI/搜索服务下拉、模型按服务隔离、用户下拉。
- 四组 Excel 导出返回 XLSX ZIP 内容。
- 测试记录按唯一所有权标识清理及测试登录退出。

若依默认会将超出范围的页码调整到有效页，脚本在检查精确分页偏移时显式传入 `reasonable=false`，没有修改业务分页行为。

验证结束后，对四张业务表的 INSERT 数据与测试前数据库快照逐行比较，均一致：模型 171 行、AI 服务 5 行、搜索服务 3 行、用户配置 5 行。正常测试操作日志与登录日志会保留，自增 ID 会前进。

本次未调用外部模型/搜索供应商，没有验证供应商密钥有效性；原二开后端提供配置管理，本身没有模型推理代理接口。非管理员角色授权、禁用用户分支及 XLSX 单元格内容未进行运行测试。

## 原生登录改造

上述 `/webui/auth/in` 是还原时的行为记录。当前该方法的 HTTP 映射已注释停用，且已从匿名白名单移除。分析工具与后台统一使用 `/captchaImage` 加 `/login` 获取原生 JWT，再携带 `Authorization: Bearer <token>` 请求新的 `GET /webui/config/current`。配置查询在 SQL 中按认证用户 ID 过滤，不接受请求指定用户；原有配置管理权限保持不变。
