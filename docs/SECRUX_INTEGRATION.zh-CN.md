# 与 Secrux 平台联动

状态：**Draft**

本文描述 IntelliJ 插件如何与 Secrux 平台联动。

## 目标

- 将 IDE 内发现的 call chain 上报为 Secrux 的**一等 findings**。
- 复用平台现有 UI 与 AI 流程（代码片段、dataflow、review 历史等）。
- 保护敏感代码：支持“仅坐标”模式，避免上传代码片段。

## 鉴权

推荐（更稳、可扩展）：

- 对接与 Console 相同 Keycloak realm 的 **OIDC Authorization Code + PKCE**。
- 插件打开系统浏览器登录，通过 loopback 回调拿到 token，并保存：
  - refresh token（PasswordSafe）
  - access token（内存中缓存，自动刷新）

兜底（更适合 v1 快速落地）：

- 插件配置中手动粘贴 access token（存入 PasswordSafe）。

## 当前服务端可复用的最小接口集

Secrux Server 已包含：

- 创建/选择任务：
  - `GET /tasks`
  - `POST /tasks`
- 导入“标准化 findings”：
  - `POST /tasks/{taskId}/findings`（`FindingBatchRequest`）
- 触发 AI Review：
  - `POST /findings/{findingId}/ai-review`

## 推荐新增接口（真正做到“上传 SARIF”复用解析）

为了直接复用服务端的 SARIF 解析逻辑（`SarifFindingParser`），避免插件重复实现解析逻辑，建议新增：

- `POST /tasks/{taskId}/findings:sarif`
  - 请求体：SARIF 2.1.0 JSON
  - 服务端：解析 → 标准化 → upsert → 发出 “FindingsReady”

说明：

- 保留现有 `POST /tasks/{taskId}/findings` 作为底层标准化导入接口。
- 新接口提供给 IDE 插件等工具直接上传 SARIF。

## SARIF 映射（插件 → Secrux）

Secrux 会从 SARIF 的 `codeFlows` 提取 dataflow（见服务端 `SarifFindingParser.extractDataflowFromSarif`）。

### 每条发现

- 每个 sink（或每条 call chain）对应一个 SARIF `result`。
- `result.ruleId`：sink id（例如 `java-runtime-exec`）
- `result.message.text`：可读摘要
- `result.locations[0]`：sink 调用点
- `result.codeFlows[0].threadFlows[0].locations[]`：链路步骤（有序）

### location message 约定

为了让 UI 显示角色（SOURCE/PROPAGATOR/SINK），插件可以用 message 前缀表达：

- `source: ...`
- `propagator: ...`
- `sink: ...`

这与服务端当前的角色推断逻辑兼容。

### 代码片段

两种模式：

1. **上传片段**：
   - 写入 `physicalLocation.region.snippet.text`
2. **仅坐标**：
   - 仅提供 `uri + startLine/startColumn/endColumn`
   - 若平台侧具备 git/归档源码，可由 Secrux/执行器解析 snippet。

## Fingerprint 生成策略

Secrux 需要每条 finding 具备稳定 `fingerprint`。

推荐：

- `fingerprint = sha256(tenant + project + commit + ruleId + sinkLocation + chainSignature)`
- 编码使用 base64url 或 hex。

## 上报流程（建议）

1. 用户在插件中选择 Secrux 的 **project** 与目标 **task**（或创建新的“IDE Audit”任务）。
2. 用户在 *Secrux Results* 中选中 call chain。
3. 插件生成 SARIF：
   - tool name：`secrux-intellij-plugin`
   - rule id：sink id
   - codeFlows：链路步骤
4. 插件调用“SARIF 导入接口”（推荐）或现有“标准化导入接口”（兜底）。
5. Secrux 保存 finding，并在 Console 中展示。
6. 可选：插件调用 `POST /findings/{findingId}/ai-review` 触发 AI Review。

## 安全注意事项

- 仅在 IntelliJ `PasswordSafe` 存储密钥。
- 对敏感仓库默认启用“仅坐标”模式。
- UI 明确提示将上报的数据范围（路径、片段、repo URL/commit）。
- 支持多租户 baseUrl，并严格校验证书（TLS）。

