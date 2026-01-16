# Contributing

Thanks for contributing to Secrux!

## Repository setup

Clone the repo and install toolchains as needed for each module.

## Development

- `apps/server` (Kotlin/Spring Boot): `./gradlew bootRun`, `./gradlew test`
- `apps/web` (Vite/React): `npm install`, `npm run dev`, `npm run build`
- `apps/ai` (Python/FastAPI): see `apps/ai/README.md`
- `apps/executor` (Go): `go test ./...`, `go build`
- `apps/engines` (engine images/scripts): see `apps/engines/README.md`

## Pull requests

- Keep changes focused and documented.
- Do not commit secrets (`.env`, tokens, private keys).
- Prefer commit messages like `<type>: <imperative summary>` (e.g. `fix: handle empty tenant header`).
