#!/usr/bin/env python3
"""Detect likely secrets without printing their values."""

from __future__ import annotations

import re
import sys
from pathlib import Path


PATTERNS = (
    (
        "credential assignment",
        re.compile(
            r"\b(?:password|passwd|pwd|db[_-]?pass|secret|token|api[_-]?key|client[_-]?secret|"
            r"парол(?:ь|я)|токен|секрет|логин)"
            r"\b\s*[:=]\s*[^\s,;]+",
            re.IGNORECASE,
        ),
    ),
    (
        "URL containing credentials",
        re.compile(r"\b[a-z][a-z0-9+.-]*://[^\s/:]+:[^\s@]+@", re.IGNORECASE),
    ),
    (
        "database endpoint",
        re.compile(r"\bjdbc:(?:jtds:)?sqlserver://[^\s\"'`]+", re.IGNORECASE),
    ),
    (
        "connection-string credential",
        re.compile(
            r"\b(?:user\s+id|uid|pwd|password)\s*=\s*[^\s;]+",
            re.IGNORECASE,
        ),
    ),
    (
        "host or IP assignment",
        re.compile(
            r"\b(?:host|server|server_name|ip|address)\b\s*[:=]\s*"
            r"(?:\d{1,3}\.){3}\d{1,3}",
            re.IGNORECASE,
        ),
    ),
    (
        "private key",
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    ),
    (
        "UNC network path",
        re.compile(r"\\\\[A-Za-z0-9._-]+\\[^\s\"'`]+"),
    ),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b")),
    ("OpenAI-style key", re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")),
    (
        "JWT-like token",
        re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"),
    ),
)

SKIP_DIRS = {".git", "__pycache__", "node_modules", "target"}


def decode_text(data: bytes) -> str | None:
    if b"\x00" in data:
        return None
    for encoding in ("utf-8-sig", "cp1251"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            pass
    return None


def iter_files(target: Path, self_path: Path):
    candidates = [target] if target.is_file() else target.rglob("*")
    for path in candidates:
        if not path.is_file() or path.resolve() == self_path:
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        yield path


def main() -> int:
    self_path = Path(__file__).resolve()
    target = Path(sys.argv[1]).expanduser().resolve() if len(sys.argv) > 1 else self_path.parents[1]
    if not target.exists():
        print(f"Target does not exist: {target}", file=sys.stderr)
        return 2

    findings: list[tuple[Path, int, str]] = []
    for path in iter_files(target, self_path):
        try:
            text = decode_text(path.read_bytes())
        except OSError as exc:
            print(f"Cannot read {path}: {exc}", file=sys.stderr)
            return 2
        if text is None:
            continue
        for line_number, line in enumerate(text.splitlines(), start=1):
            for label, pattern in PATTERNS:
                if pattern.search(line):
                    findings.append((path, line_number, label))

    if findings:
        print("Potential secrets found; matched values are intentionally hidden:", file=sys.stderr)
        for path, line_number, label in findings:
            print(f"{path}:{line_number}: {label}", file=sys.stderr)
        return 1

    print(f"No likely secrets found in {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
