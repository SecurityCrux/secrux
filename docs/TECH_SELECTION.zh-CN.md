# 技术选型

状态：**Draft**

本文记录 Secrux IntelliJ 插件（目标 IntelliJ IDEA **2025.2+**）的关键技术选择。

## 约束条件

- 需要面向 IntelliJ IDEA **2025.2+**，并采用“新版”插件构建方式（Gradle + Kotlin DSL）。
- 尽量 **减少外部依赖**（降低与 IDE 类路径冲突风险，简化分发）。
- 核心解析层必须使用 **PSI/UAST**（覆盖 Java/Kotlin 项目）。
- 需要支持可取消的后台计算，避免阻塞 UI 线程。

## 构建体系

### 备选方案

- 旧版 Gradle IntelliJ Plugin（`org.jetbrains.intellij`）
- 新版 IntelliJ Platform Gradle Plugin（`org.jetbrains.intellij.platform` / 官方模板风格）

### 结论

选择 **IntelliJ Platform Gradle Plugin**，并以官方 **IntelliJ Platform Plugin Template** 的结构落地。

原因：

- 更贴合当前与未来 IntelliJ 版本（包含 2025.2+）。
- 长期维护更稳定：平台依赖管理、Verifier、签名与发布能力更完善。
- Kotlin DSL 友好。

## 开发语言

结论：**Kotlin**

原因：

- IntelliJ 插件开发一等公民。
- 与 UI DSL、平台 API 配合更顺畅。
- 便于实现持久化状态与数据模型序列化。

## 代码解析（PSI/UAST）

结论：**UAST 优先，PSI 兜底**。

- 主流程基于 `org.jetbrains.uast.*`，用一套 visitor 兼容 Java/Kotlin。
- 当 UAST resolve 不完整时，兜底使用：
  - Java PSI（`PsiMethod`、`PsiCallExpression`）
  - `SmartPsiElementPointer` 用于导航稳定性。

说明：

- 调用解析依赖 `UCallExpression.resolve()` / `PsiReference.resolve()`。
- 在索引阶段（Dumb Mode）分析可能延迟或不完整，UI 需要能提示该状态。

## CallGraph 表达

结论：自研轻量邻接表（不引入重型图依赖）。

- `Map<MethodId, MethodNode>`
- `Map<MethodId, List<CallEdge>>`（出边）
- `Map<MethodId, List<CallEdge>>`（入边，用于反向搜索）

原因：

- 开销可控、可预测。
- 便于持久化与版本管理。
- 避免引入额外图计算依赖。

## CallChain 搜索算法

结论：带上限的反向 DFS/BFS + 路径枚举。

可配置限制：

- `maxDepth`
- `maxPathsPerSink`
- `maxNodesPerPath`
- 环检测

原因：

- 路径数量可能爆炸，必须有硬性限制保障可用性与稳定性。

## UI 技术

结论：IntelliJ 平台 UI + Kotlin UI DSL。

- Tool windows：左侧（Secrux）+ 下栏（Secrux Results）
- 表格：`JBTable` / `TableView`
- 树：`SimpleTree` / `JTree` + Speed Search

原因：

- 与 IDE 视觉一致。
- 尽量兼容 Community/Ultimate。

## 高亮方案

结论：扫描结果驱动的运行期高亮。

- 通过 `MarkupModel` 添加 `RangeHighlighter`。
- 项目级别维护每个 `VirtualFile` 的高亮注册表。

原因：

- v1 避免常驻 Inspection 带来的持续开销。

未来：

- 可选实现 `LocalInspectionTool`，提供持续高亮与 quick fix。

## 持久化

结论：IDE 持久状态 + 项目本地大文件产物分层。

- `PersistentStateComponent`：配置、sink 列表选择、备忘录、上次运行元信息。
- 大文件：CallGraph 与 sink 索引存入 `.idea/secrux/`（或 `.secrux/idea/`）。

原因：

- 重启后可恢复。
- 可做到“重载而不重算”。

## HTTP 与鉴权（平台联动）

结论：

- HTTP：使用 IntelliJ 的 `HttpRequests`（自动尊重 IDE 代理/证书等设置）。
- 密钥：使用 IntelliJ `PasswordSafe` 保存 token/refresh token。
- 推荐鉴权：OIDC Authorization Code + PKCE（系统浏览器登录 + loopback 回调）。

详见：`SECRUX_INTEGRATION.zh-CN.md`

## SARIF 生成

结论：直接生成 SARIF 2.1.0 JSON（最小字段集合）。

- 仅保留 Secrux 需要的字段：
  - `runs[].tool.driver.name`
  - `runs[].results[]`（`locations` + `codeFlows`）
- CallChain 写入 `codeFlows.threadFlows.locations[]`

原因：

- 不引入大型 SARIF 依赖，且更易控制输出结构。

## 测试策略

结论：

- 单测：纯 Kotlin 模块（sink 匹配、SARIF 生成、图搜索辅助逻辑）。
- 集成测试：使用 IntelliJ Test Framework，在 fixture ��目��验证 PSI/UAST 行为。

## 兼容性说明

- 以 IntelliJ 2025.2+ 使用的 JBR/JDK 为准（大概率 Java 21）。
- 避免依赖 Ultimate-only API，以便尽可能兼容 Community。

