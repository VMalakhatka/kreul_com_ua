#!/usr/bin/env python3
"""Validate that the Paint_Rus experiment SQL pack remains guarded/read-only."""

from __future__ import annotations

import re
import sys
from pathlib import Path


EXPECTED_SQL = (
    "01_preflight_paint_rus.sql",
    "02_document_snapshot.sql",
    "03_debt_probe.sql",
    "04_accounting_price_control.sql",
    "05_object_contract_export.sql",
    "07_candidate_selection.sql",
    "08_client_search.sql",
    "09_client_activity.sql",
    "10_document_full_detail.sql",
    "11_payment_full_detail.sql",
)

FORBIDDEN = (
    ("INSERT", re.compile(r"\bINSERT\b", re.IGNORECASE)),
    ("UPDATE", re.compile(r"\bUPDATE\b", re.IGNORECASE)),
    ("DELETE", re.compile(r"\bDELETE\b", re.IGNORECASE)),
    ("MERGE", re.compile(r"\bMERGE\b", re.IGNORECASE)),
    ("SELECT INTO", re.compile(r"\bSELECT\b[\s\S]*?\bINTO\b", re.IGNORECASE)),
    ("TRUNCATE", re.compile(r"\bTRUNCATE\b", re.IGNORECASE)),
    ("CREATE", re.compile(r"\bCREATE\b", re.IGNORECASE)),
    ("ALTER", re.compile(r"\bALTER\b", re.IGNORECASE)),
    ("DROP", re.compile(r"\bDROP\b", re.IGNORECASE)),
    ("BACKUP", re.compile(r"\bBACKUP\b", re.IGNORECASE)),
    ("RESTORE", re.compile(r"\bRESTORE\b", re.IGNORECASE)),
    ("DBCC", re.compile(r"\bDBCC\b", re.IGNORECASE)),
    ("KILL", re.compile(r"\bKILL\b", re.IGNORECASE)),
    ("GRANT", re.compile(r"\bGRANT\b", re.IGNORECASE)),
    ("REVOKE", re.compile(r"\bREVOKE\b", re.IGNORECASE)),
    ("DENY", re.compile(r"\bDENY\b", re.IGNORECASE)),
    ("xp_cmdshell", re.compile(r"\bxp_cmdshell\b", re.IGNORECASE)),
    ("OLE automation", re.compile(r"\bsp_OA[A-Za-z]+\b", re.IGNORECASE)),
)


def strip_comments_and_strings(sql: str) -> str:
    """Remove comments/literals so words in documentation do not trigger rules."""
    result: list[str] = []
    index = 0
    state = "code"
    while index < len(sql):
        current = sql[index]
        following = sql[index + 1] if index + 1 < len(sql) else ""

        if state == "code":
            if current == "-" and following == "-":
                state = "line_comment"
                result.extend("  ")
                index += 2
                continue
            if current == "/" and following == "*":
                state = "block_comment"
                result.extend("  ")
                index += 2
                continue
            if current == "'":
                state = "string"
                result.append(" ")
                index += 1
                continue
            result.append(current)
            index += 1
            continue

        if state == "line_comment":
            if current in "\r\n":
                state = "code"
                result.append(current)
            else:
                result.append(" ")
            index += 1
            continue

        if state == "block_comment":
            if current == "*" and following == "/":
                state = "code"
                result.extend("  ")
                index += 2
            else:
                result.append(current if current in "\r\n" else " ")
                index += 1
            continue

        if state == "string":
            if current == "'" and following == "'":
                result.extend("  ")
                index += 2
            elif current == "'":
                state = "code"
                result.append(" ")
                index += 1
            else:
                result.append(current if current in "\r\n" else " ")
                index += 1

    return "".join(result)


def default_pack_path() -> Path:
    skill_dir = Path(__file__).resolve().parents[1]
    project_root = skill_dir.parents[2]
    return project_root / "docs" / "folio-experiments"


def main() -> int:
    pack = Path(sys.argv[1]).expanduser().resolve() if len(sys.argv) > 1 else default_pack_path()
    errors: list[str] = []

    if not pack.is_dir():
        print(f"Experiment pack directory not found: {pack}", file=sys.stderr)
        return 2

    for name in EXPECTED_SQL:
        path = pack / name
        if not path.is_file():
            errors.append(f"{name}: required file is missing")
            continue

        raw = path.read_text(encoding="utf-8")
        code = strip_comments_and_strings(raw)

        if "CONVERT(varbinary(128), DB_NAME()) <> CONVERT(varbinary(128), 'Paint_Rus')" not in raw:
            errors.append(f"{name}: exact Paint_Rus database guard is missing")

        for label, pattern in FORBIDDEN:
            if pattern.search(code):
                errors.append(f"{name}: forbidden read-only token {label}")

        exec_lines = [
            line.strip()
            for line in code.splitlines()
            if re.search(r"\bEXEC(?:UTE)?\b", line, re.IGNORECASE)
        ]
        if name == "03_debt_probe.sql":
            allowed = ("dbo.I_DOLG_DOC", "dbo.I_DOLG_HIS")
            if len(exec_lines) != 2 or any(
                not any(procedure in line for procedure in allowed)
                for line in exec_lines
            ):
                errors.append(
                    f"{name}: only one call each to I_DOLG_DOC/I_DOLG_HIS is allowed"
                )
        elif exec_lines:
            errors.append(f"{name}: executable procedure/dynamic SQL call is not allowed")

    ignore_file = default_pack_path().parents[1] / ".gitignore"
    if ignore_file.is_file():
        ignore_text = ignore_file.read_text(encoding="utf-8")
        if "/docs/folio-experiments/*.rpt" not in ignore_text:
            errors.append(".gitignore: raw Paint_Rus .rpt reports are not ignored")

    if errors:
        print("Experiment pack validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"Paint_Rus experiment pack is guarded and read-only: {pack}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
