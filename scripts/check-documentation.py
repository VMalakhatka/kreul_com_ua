#!/usr/bin/env python3
"""Validate KREUL Java documentation and changed-path impact."""

from __future__ import annotations

import argparse
import fnmatch
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote


LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
REQUIRED = ("README.md", "docs/README.md")


@dataclass(frozen=True)
class ImpactRule:
    name: str
    source_patterns: tuple[str, ...]
    documentation_patterns: tuple[str, ...]


IMPACT_RULES = (
    ImpactRule(
        "HTTP contract",
        (
            "src/main/java/**/controller/**",
            "src/main/java/**/dto/**",
        ),
        ("docs/api/**",),
    ),
    ImpactRule(
        "Folio business and data access",
        (
            "src/main/java/**/service/folio/**",
            "src/main/java/**/dao/folio/**",
        ),
        (
            "docs/api/**",
            "docs/business/**",
            "docs/00_DATABASE_CATALOG.md",
            ".agents/skills/work-with-folio-mssql/**",
        ),
    ),
    ImpactRule(
        "MariaDB projections and migrations",
        (
            "src/main/java/**/dao/wp/**",
            "src/main/resources/db/wp/migration/**",
        ),
        ("docs/api/**", "docs/README.md"),
    ),
    ImpactRule(
        "Java runtime, environment and deploy",
        (
            "Dockerfile",
            "docker-compose*.yml",
            "deploy*.sh",
            "pom.xml",
            "src/main/resources/application.properties",
        ),
        ("README.md", "docs/README.md"),
    ),
    ImpactRule(
        "Folio project skill",
        (
            ".agents/skills/work-with-folio-mssql/SKILL.md",
            ".agents/skills/work-with-folio-mssql/references/**",
            ".agents/skills/work-with-folio-mssql/scripts/**",
        ),
        (
            ".agents/skills/work-with-folio-mssql/**",
            "docs/README.md",
            "docs/api/**",
            "docs/business/**",
        ),
    ),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate Markdown and Java changed-path documentation impact."
    )
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--working-tree", action="store_true")
    modes.add_argument("--staged", action="store_true")
    modes.add_argument("--base", metavar="REF")
    modes.add_argument("--changed-file", action="append")
    return parser.parse_args()


def git_paths(root: Path, arguments: list[str]) -> set[str]:
    result = subprocess.run(
        ["git", *arguments], cwd=root, check=False, capture_output=True, text=True
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or "git command failed"
        raise RuntimeError(detail)
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def changed_paths(root: Path, args: argparse.Namespace) -> set[str]:
    if args.staged:
        return git_paths(root, ["diff", "--cached", "--name-only", "--diff-filter=ACMR"])
    if args.base:
        return git_paths(root, ["diff", "--name-only", "--diff-filter=ACMR", f"{args.base}...HEAD"])
    if args.working_tree:
        paths = git_paths(root, ["diff", "--name-only", "--diff-filter=ACMR"])
        paths.update(git_paths(root, ["diff", "--cached", "--name-only", "--diff-filter=ACMR"]))
        paths.update(git_paths(root, ["ls-files", "--others", "--exclude-standard"]))
        return paths
    return set(args.changed_file or ())


def matches(path: str, patterns: tuple[str, ...]) -> bool:
    return any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)


def markdown_files(root: Path) -> list[Path]:
    files = [root / "README.md"]
    files.extend((root / "docs").rglob("*.md"))
    skill = root / ".agents" / "skills" / "work-with-folio-mssql"
    files.append(skill / "SKILL.md")
    files.extend((skill / "references").rglob("*.md"))
    return sorted({path.resolve() for path in files if path.is_file()})


def documentation_impact_errors(changed: set[str]) -> list[str]:
    errors: list[str] = []
    for rule in IMPACT_RULES:
        sources = sorted(path for path in changed if matches(path, rule.source_patterns))
        if not sources:
            continue
        if any(matches(path, rule.documentation_patterns) for path in changed):
            continue
        examples = ", ".join(sources[:3])
        expected = ", ".join(rule.documentation_patterns)
        errors.append(
            f"documentation impact missing for {rule.name}: {examples}; "
            f"update one relevant document ({expected})"
        )

    covered = tuple(
        pattern for rule in IMPACT_RULES for pattern in rule.source_patterns
    )
    other_main = sorted(
        path
        for path in changed
        if path.startswith("src/main/")
        and not matches(path, covered)
        and not path.endswith((".md", ".txt"))
    )
    if other_main and not any(
        path == "README.md" or path.startswith("docs/") and path.endswith(".md")
        for path in changed
    ):
        examples = ", ".join(other_main[:5])
        errors.append(
            "documentation impact missing for other Java source: "
            f"{examples}; update README.md or the owning docs/api or docs/business file"
        )
    return errors


def link_errors(root: Path) -> tuple[list[str], int]:
    errors: list[str] = []
    checked = 0
    for source in markdown_files(root):
        checked += 1
        content = source.read_text(encoding="utf-8")
        for raw_target in LINK_RE.findall(content):
            target = raw_target.strip().strip("<>")
            if not target or target.startswith(("#", "/", "mailto:")):
                continue
            if "://" in target:
                continue
            target = unquote(target.split("#", 1)[0])
            if target and not (source.parent / target).resolve().exists():
                errors.append(
                    f"broken link: {source.relative_to(root)} -> {raw_target}"
                )
    return errors, checked


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    errors = [
        f"missing required document: {relative}"
        for relative in REQUIRED
        if not (root / relative).is_file()
    ]
    links, checked = link_errors(root)
    errors.extend(links)

    try:
        changed = changed_paths(root, args)
    except RuntimeError as error:
        errors.append(f"cannot determine changed paths: {error}")
        changed = set()
    errors.extend(documentation_impact_errors(changed))

    if errors:
        print("Documentation validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    mode = "structure and links"
    if args.working_tree:
        mode = f"working-tree impact ({len(changed)} paths)"
    elif args.staged:
        mode = f"staged impact ({len(changed)} paths)"
    elif args.base:
        mode = f"impact since {args.base} ({len(changed)} paths)"
    elif args.changed_file:
        mode = f"explicit impact ({len(changed)} paths)"
    print(f"Documentation validation passed: {mode}; {checked} Markdown files checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
