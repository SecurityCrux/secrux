from __future__ import annotations

import os
from pathlib import Path
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

# Configuration knobs. Override via environment variables when deploying.
BASE_DIR = Path(os.getenv("FILE_READER_ROOT", os.getcwd())).resolve()
MAX_LINES = int(os.getenv("FILE_READER_MAX_LINES", "400"))
MAX_LINE_LENGTH = int(os.getenv("FILE_READER_MAX_LINE_LENGTH", "2000"))
ALLOWED_EXTENSIONS = tuple(
    ext.strip().lower() for ext in os.getenv("FILE_READER_ALLOWED_EXTS", "").split(",") if ext.strip()
)

app = FastAPI(title="File Reader MCP", version="0.1.0")


class LineRange(BaseModel):
    start_line: int = Field(default=1, ge=1, alias="startLine")
    end_line: Optional[int] = Field(default=None, ge=1, alias="endLine")

    model_config = {"populate_by_name": True}


class FileReadRequest(BaseModel):
    path: str = Field(..., description="Relative path underneath FILE_READER_ROOT")
    line_range: Optional[LineRange] = Field(default=None, alias="lineRange")
    encoding: str = Field(default="utf-8")

    model_config = {"populate_by_name": True}


class LineResponse(BaseModel):
    line: int
    text: str


class FileReadResponse(BaseModel):
    path: str
    totalLines: int
    returnedLines: int
    truncated: bool
    range: LineRange
    content: List[LineResponse]


def _ensure_within_base(target: Path) -> Path:
    try:
        candidate = target.resolve()
    except OSError as exc:
        raise HTTPException(status_code=400, detail=f"Unable to resolve path: {exc}") from exc
    if not str(candidate).startswith(str(BASE_DIR)):
        raise HTTPException(status_code=400, detail="Requested file escapes configured FILE_READER_ROOT")
    if not candidate.is_file():
        raise HTTPException(status_code=404, detail="File not found")
    if ALLOWED_EXTENSIONS:
        suffix = candidate.suffix.lower()
        if suffix not in ALLOWED_EXTENSIONS:
            raise HTTPException(status_code=400, detail="File extension not allowed")
    return candidate


def _read_lines(path: Path, start_line: int, end_line: Optional[int], encoding: str) -> tuple[List[LineResponse], bool, int]:
    if end_line is not None and end_line < start_line:
        raise HTTPException(status_code=400, detail="endLine must be greater than or equal to startLine")

    lines: List[LineResponse] = []
    truncated = False
    total_lines = 0
    try:
        with path.open("r", encoding=encoding, errors="replace") as handle:
            for idx, raw_line in enumerate(handle, start=1):
                total_lines = idx
                if idx < start_line:
                    continue
                if end_line is not None and idx > end_line:
                    break
                if len(lines) >= MAX_LINES:
                    truncated = True
                    break
                text = raw_line.rstrip("\r\n")
                if len(text) > MAX_LINE_LENGTH:
                    text = text[:MAX_LINE_LENGTH] + "…"
                    truncated = True
                lines.append(LineResponse(line=idx, text=text))
    except UnicodeDecodeError as exc:
        raise HTTPException(status_code=400, detail=f"Unable to decode file using {encoding}") from exc

    if total_lines and start_line > total_lines:
        raise HTTPException(status_code=416, detail="Requested line range is outside of file length")

    return lines, truncated, total_lines


@app.get("/healthz")
def health() -> dict:
    return {"status": "ok", "root": str(BASE_DIR)}


def _build_response(path: Path, line_range: LineRange, encoding: str) -> FileReadResponse:
    content, truncated, total_lines = _read_lines(path, line_range.start_line, line_range.end_line, encoding)
    response_range = LineRange(
        start_line=line_range.start_line,
        end_line=(content[-1].line if content else line_range.start_line),
    )
    return FileReadResponse(
        path=str(path.relative_to(BASE_DIR)),
        totalLines=total_lines,
        returnedLines=len(content),
        truncated=truncated,
        range=response_range,
        content=content,
    )


def _prepare_path(request: FileReadRequest) -> Path:
    relative_path = request.path.strip()
    if not relative_path:
        raise HTTPException(status_code=400, detail="path must not be empty")
    return _ensure_within_base(BASE_DIR / relative_path)


@app.post("/tools/file.read.full:invoke", response_model=FileReadResponse)
def read_full_file(request: FileReadRequest) -> FileReadResponse:
    safe_path = _prepare_path(request)
    line_range = request.line_range or LineRange()
    return _build_response(safe_path, line_range, request.encoding)


@app.post("/tools/file.read.range:invoke", response_model=FileReadResponse)
def read_range_file(request: FileReadRequest) -> FileReadResponse:
    if request.line_range is None:
        raise HTTPException(status_code=400, detail="lineRange must be provided for range reads")
    safe_path = _prepare_path(request)
    return _build_response(safe_path, request.line_range, request.encoding)
