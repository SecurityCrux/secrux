# 贡献指南

欢迎参与 Secrux 的开发与改进！

## 仓库拉取

直接克隆仓库，并按模块安装对应的开发工具链即可。

## 开发与测试

- `apps/server`（Kotlin/Spring Boot）：`./gradlew bootRun`、`./gradlew test`
- `apps/web`（Vite/React）：`npm install`、`npm run dev`、`npm run build`
- `apps/ai`（Python/FastAPI）：参考 `apps/ai/README.zh-CN.md`
- `apps/executor`（Go）：`go test ./...`、`go build`
- `apps/engines`（引擎镜像/脚本）：参考 `apps/engines/README.zh-CN.md`

## PR 提交建议

- 改动尽量聚焦，并补齐文档说明。
- 不要提交敏感信息（`.env`、token、私钥等）。
- 建议使用 `<type>: <imperative summary>` 的提交信息格式（例如：`fix: xxx`）。
