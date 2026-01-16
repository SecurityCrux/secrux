# Secrux IntelliJ 插件

面向 Secrux 的静态代码审计辅助插件，基于 IntelliJ PSI/UAST。

当前目录先提供设计文档 + 技术选型文档，后续实现会按这些文档落地。

## 构建与运行（开发）

环境要求：

- JDK 21
- 需要联网（Gradle 会下载 IntelliJ SDK 与依赖）

常用命令：

- 启动带插件的 IDE：`cd apps/intellij-plugin && ../gradle-8.10.2/bin/gradle runIde`
- 构建插件 ZIP：`cd apps/intellij-plugin && ../gradle-8.10.2/bin/gradle buildPlugin`

## 当前 MVP

- Tool window：左侧 **Secrux**，下栏 **Secrux Results**
- 审计备忘录：编辑器右键 → **加入审计备忘录** → 双击条目跳转
- Sink 扫描（仅 main 源码）：点击 **Scan sinks** → 对命中位置高亮，并在结果表中展示
- 跳转：双击表格行跳转到对应代码位置
- CallGraph：点击 **构建调用图**（持久化到 `.idea/secrux/callgraph.json`），支持重载/清理
- CallChains：选中表格行 → **查看调用链** → 双击链路节点跳转（仅限项目内方法）
- Sink 列表：支持内置列表或项目自定义 JSON 文件（参考 `apps/intellij-plugin/docs/sink-catalog.example.json`）
- 上报（手动 token + 手动严重性）：选中表格行 → **Report selected** → 选择 severity 并上传（可附带代码片段）

## 规划功能

0. **CallGraph 构建**：基于 UAST 构建项目 CallGraph，可选择持久化到文件并支持重载，也可仅保存在内存。
1. **代码审计备忘录**：右键 PSI/UAST 节点（方法/字段/变量等）加入备忘录，点击可跳回代码位置。
2. **Sink 点搜索**：根据可配置的 sink 列表初始化并扫描，IDE 内高亮，并在下栏展示（文件名、位置、类型等）。
3. **CallChain 匹配**：对每个 sink 反向搜索 CallChain（支持深度/路径上限）。
4. **入口点定位**：识别常见 Java/Kotlin 入口点（Spring MVC、JAX-RS、Micronaut、Quarkus Routes、Servlet、WebSocket、gRPC、Dubbo、`main`），反向搜索可选“仅展示触及入口点的”链路。
5. **与 Secrux 平台联动**：将 call chain 以 SARIF 2.1.0（`codeFlows`）上报，复用平台现有 Findings/AI Review 能力。
6. **其他能力**：可扩展动作（导出、基线对比、快速标记/分流等）。

## 文档

- `apps/intellij-plugin/docs/README.md`
- `apps/intellij-plugin/docs/ARCHITECTURE.md`
- `apps/intellij-plugin/docs/TECH_SELECTION.md`
- `apps/intellij-plugin/docs/SECRUX_INTEGRATION.md`
- `apps/intellij-plugin/docs/ROADMAP.md`

## 目标 IDE

- IntelliJ IDEA **2025.2+**（支持 Community）。
