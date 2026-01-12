# 路线图

状态：**Draft**

## Phase 0 — 项目初始化

- 工程脚手架（Gradle、plugin.xml、runIde）
- Tool window 骨架（左侧 + 下栏）
- 配置存储（PersistentStateComponent + PasswordSafe）

## Phase 1 — 审计备忘录 MVP

- 编辑器右键“加入审计备忘录”
- 备忘录列表 UI + 跳转
- 稳定持久化

## Phase 2 — CallGraph MVP（UAST）

- 构建按钮 + 进度 + 可取消
- 内存图结构
- 图产物保存/重载

## Phase 3 — Sink 扫描与高亮

- Sink 列表格式 + 内置 presets
- 项目扫描输出 sink matches
- 编辑器高亮 + 结果表格 + 点击跳转

## Phase 4 — CallChain 追踪

- 反向搜索链路（带上限）
- 入口点识别（Spring Controller）
- 链路过滤：“必须触达入口点”

## Phase 5 — 与 Secrux 平台联动

- 平台连接配置（baseUrl/tenant/project/task）
- SARIF 生成（`codeFlows`）用于 call chain
- 上报流程 + 历史记录

## Phase 6 — AI Review 闭环

- 通过 Secrux API 触发 AI Review
- 轮询状态 + 在 IDE 展示摘要

## Phase 7 — 质量与可扩展性

- 增量图重建（按文件/模块变更）
- 动态分发/继承场景的更好解析策略
- 性能 Profiling + 缓存 + 上限调优

