# 架构设计

状态：**Draft**

## 目标

- 基于 IntelliJ **PSI/UAST** 做*静态*代码理解，服务于代码审计工作流。
- 提供高效闭环：构建 CallGraph → 定位 sinks → 追踪 call chain → 记录审计结论/备忘。
- 支持派生产物（CallGraph、sink 匹配结果、备忘录）持久化与重载，避免重复计算。
- 与 **Secrux** 平台联动：将 call chain 以 **SARIF 2.1.0**（`codeFlows`）上报，复用平台 Findings + AI Review 流程。

## 非目标（v1）

- 覆盖所有 JVM 语言的完整过程间数据流/污点分析。
- 追求完备性/健全性（反射、动态代理、依赖注入分发等场景）。
- 覆盖 Spring MVC 以外的所有框架入口点模式。

## 交互与布局

插件使用 **两个 Tool Window**：

- **左侧**：*Secrux*（功能区 + 导航）
  - 按钮：Build CallGraph / Reload Graph / Clear Cache
  - 面板：Memo / Sinks / Entry Points / Settings / Platform
- **下栏**：*Secrux Results*（展示区）
  - 表格：Sink matches、Call chains、上报历史
  - 详情：链路步骤预览（step + 代码片段）

编辑器集成：

- 在 PSI/UAST 目标上右键菜单提供动作：
  - “加入审计备忘录”
  - “标记为 Sink（本地）”
  - “查找调用者 / 查找被调用者”
  - “上报 CallChain 到 Secrux”

## 核心组件

### CallGraph

**职责**

- 构建并维护“方法级”的有向图。
- 尽可能记录边的**调用点位置**（call site）。

**核心概念（抽象）**

- `MethodId`：稳定标识（FQN + 签名 + 文件路径兜底）
- `MethodNode`：方法元信息（名称、类、文件、范围）
- `CallSite`：文件 + 行列 + snippet hash（可选）
- `CallEdge`：`caller -> callee` + `CallSite`

**构建方式**

- 基于 UAST 遍历项目源文件。
- 对每个调用表达式：
  - 尝试 resolve 目标方法。
  - 记录 edge + call site。
  - 若不可解析，记录为 external/unknown，并保留可读标签。

### Sink Registry + Sink Scan

**Sink 定义**（规划）

- 以“已解析的方法调用”为主要匹配对象：
  - `classFqn`
  - `methodName`
  - 可选 `parameterTypes`
  - 可选语言/模块过滤

Sink 初始化来源：

- 内置 presets（插件内置）
- 用户自定义文件（YAML/JSON，按项目）

扫描产物：

- `SinkMatch` 列表：
  - sink id / category
  - 文件 + 位置
  - 所属方法 `MethodId`

IDE 高亮：

- 使用编辑器 range highlighter 标记 sink 位置。
- 维护注册表，确保新打开的编辑器也能重新打高亮。

### CallChain 追踪

基于 CallGraph 计算 call chain：

- 反向搜索：`sinkEnclosingMethod <- callers <- ...`
- 控制项：
  - 最大深度
  - 每个 sink 最大路径数
  - 环处理
  - 外部节点处理策略

可选过滤：

- 仅展示触达至少一个 **Entry Point** 的链路。

### 入口点识别（Java/Kotlin）

入口点识别基于常见框架模式（尽力而为）：

- Spring MVC：`@RestController` / `@Controller` + `@RequestMapping` / `@GetMapping` / ...
- JAX-RS（Jersey/RESTEasy/Quarkus/Dropwizard）：`@Path` + `@GET` / `@POST` / ...（也支持 `@HttpMethod` 元注解）
- Micronaut：`@Controller` + `@Get` / `@Post` / ...
- Quarkus Vert.x Routes：`@Route` / `@RouteFilter`
- Servlet 容器：`HttpServlet#doGet/doPost/.../service`、`Filter#doFilter`、`Servlet#service`
- WebSocket：`@ServerEndpoint` + `@OnOpen/@OnClose/@OnMessage/@OnError`
- gRPC：实现 `BindableService` 且方法签名包含 `StreamObserver`
- Dubbo：`@DubboService`（以及 Dubbo 的 `@Service`）且方法覆盖接口方法（或 `$invoke`）
- 普通 JVM 应用：`public static main(...)`

当前入口点以 `MethodRef` id 的形式记录在 CallGraph 的 `entryPoints` 中（见 `.idea/secrux/callgraph.json`）。

### 审计备忘录

备忘录是项目本地的 pinned 列表：

- 运行期保存为 `SmartPsiElementPointer`
- 持久化保存为稳定 `MemoItemId`，例如：
  - `{ filePath, fqName, signature, textRangeHash }`

### 持久化

两层存储：

1. **轻量状态**（配置、备忘录、上次扫描时间等）：
   - IntelliJ `PersistentStateComponent`
2. **重型产物**（CallGraph + sink 索引）：
   - 推荐存储在项目目录：
     - `.idea/secrux/` 或 `.secrux/idea/`
   - 文件格式带版本：
     - `graph.formatVersion`
     - `projectFingerprint`（路径/模块/git HEAD 可选）

### 后台任务与取消

- 构建/扫描均在可取消的后台任务中执行。
- PSI/UAST 访问遵循 ReadAction，并尊重 IDE 索引状态（Dumb Mode）。

## 上报到 Secrux（概览）

插件为“审计发现”生成 SARIF 报告：

- 每个 sink（或每条链路）对应一个 SARIF `result`。
- `codeFlows` 记录 call chain 的步骤位置。
- 每步可选附带代码片段：
  - 写入 SARIF `region.snippet.text`
  - 或仅上报坐标，由 Secrux/执行器从 git/归档中解析 snippet

详见：`secrux-intellij-plugin/docs/SECRUX_INTEGRATION.zh-CN.md`

## 已知限制

- 静态 CallGraph 只能近似（动态分发、反射、Lombok、代理等）。
- Kotlin/Java 混合项目在索引阶段可能出现部分节点无法解析。
- 大型项目需要增量/缓存策略，否则耗时较长。
