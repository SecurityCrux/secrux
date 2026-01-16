# File Reader MCP

> 提示：AI Service 已默认内置该 MCP（见 `/builtin/file-reader` 路由），无需上传。此样例仓库主要用于本地调试或在需要自定义功能时参考。

This sample MCP exposes two tools over HTTP so Secrux agents can request file snippets with line numbers:

- `file.read.full` – returns the file (subject to max line/length limits).
- `file.read.range` – requires `lineRange` (`startLine`/`endLine`) and only returns that span.

It is implemented with FastAPI and performs several guard checks to prevent path traversal
and runaway responses.

## Features

- Enforces a configurable base directory (`FILE_READER_ROOT`) so requests cannot leave the
  approved repository/workspace. All paths are normalized and rejected if they escape.
- Optional allow-list of file extensions via `FILE_READER_ALLOWED_EXTS` (e.g. `.py,.kt,.md`).
- Supports whole-file reads or bounded ranges (`startLine`, `endLine`) with line numbers in the response.
- Caps the number of lines and per-line length to avoid very large responses. Defaults:
  `FILE_READER_MAX_LINES=400`, `FILE_READER_MAX_LINE_LENGTH=2000`.
- `GET /healthz` endpoint so the platform can verify the MCP is alive.

## Local development

```bash
cd apps/ai/samples/mcps/file_reader
python -m venv .venv
source .venv/bin/activate
pip install -e .

export FILE_READER_ROOT=/path/to/your/repo
export FILE_READER_ALLOWED_EXTS=".py,.kt,.md"
uvicorn file_reader.server:app --host 0.0.0.0 --port 7070
```

Example invocation (range):

```bash
curl -X POST http://localhost:7070/tools/file.read.range:invoke \
  -H "Content-Type: application/json" \
  -d '{"path":"apps/server/src/main/resources/application.yml","lineRange":{"startLine":1,"endLine":60}}'
```

Full file (up to guard limits):

```bash
curl -X POST http://localhost:7070/tools/file.read.full:invoke \
  -H "Content-Type: application/json" \
  -d '{"path":"README.md"}'
```

The response includes the normalized path, total line count, and an array of
`{"line": <number>, "text": "<contents>"}`.

## Packaging & uploading to Secrux

1. Bundle the plugin:
   ```bash
   cd apps/ai/samples/mcps/file_reader
   zip -r ../file-reader-mcp.zip file_reader README.md pyproject.toml
   ```
2. In the console, open **AI Clients → MCP Profiles** and click **Upload MCP**.
3. Select `file-reader-mcp.zip`, choose a name (e.g. `local-file-reader`), and
   set the entrypoint to `file_reader.server:app`.
4. Provide JSON params describing how the AI service should run the MCP. Example:
   ```json
   {
     "port": 7070,
     "command": "uvicorn file_reader.server:app --host 0.0.0.0 --port 7070",
     "env": {
       "FILE_READER_ROOT": "/workspace/repos/my-tenant",
       "FILE_READER_ALLOWED_EXTS": ".py,.kt,.md"
     }
   }
   ```
   When you use the upload flow the service automatically injects `rootPath`
   (where the archive is extracted) so you can point `FILE_READER_ROOT` at that
   directory or a bind-mounted workspace.
5. Create or update AI agents referencing the MCP profile, e.g.
   - `{ "tool": "file.read.full" }` for全文件读取。
   - `{ "tool": "file.read.range" }` for按行范围读取。

Once the MCP profile is enabled and the agent references it, Secrux will route
`file.read.*` tool invocations through this service, letting agents inspect files
with fine-grained line ranges while respecting the sandbox boundaries.
