#!/usr/bin/env python3
"""Read-only packaged-JAR gate for the 2026-10-31 profit scope recovery.

Checks packaging, not live HTTP, authorization, DB state or build provenance.
Never starts Java/Flyway, reads environment files or extracts application secrets.
"""
import argparse
import hashlib
from pathlib import Path
import re
import sys
from zipfile import BadZipFile, ZipFile
import zlib

PREFIX = 'BOOT-INF/classes/'
REQUIRED = (
    'org/example/proect/lavka/controller/FolioProfitScopeSettingsController.class',
    'org/example/proect/lavka/service/folio/FolioProfitScopeSettingsService.class',
    'org/example/proect/lavka/dao/wp/FolioProfitScopeSettingsDao.class',
    'org/example/proect/lavka/dao/folio/FolioProfitScopeCatalogDao.class',
    'org/example/proect/lavka/controller/FullSyncJobController.class',
    'org/example/proect/lavka/config/WpFlywayMigrationConfiguration.class',
    'org/example/proect/lavka/config/migration/V12__folio_product_analytics_schema_v4.class',
)
CHECKSUMS = {12: -1886659825, 19: -1642929544, 20: 497939418}


def checksum(sql):
    # Flyway's SQL checksum: UTF-8 lines, BOM/line endings omitted, signed CRC32.
    crc = 0
    for line in sql.decode('utf-8-sig').splitlines():
        crc = zlib.crc32(line.encode('utf-8'), crc)
    return crc if crc < 2**31 else crc - 2**32


def verify(path):
    problems = []
    with ZipFile(path) as jar:
        names = jar.namelist()
        for required in REQUIRED:
            if PREFIX + required not in names:
                problems.append('Missing ' + required)
        migrations = {}
        for name in names:
            match = re.fullmatch(PREFIX + r'db/wp/(?:migration|vendor/mariadb)/V(\d+)__[^/]+\.sql', name)
            if match:
                migrations.setdefault(int(match[1]), []).append(name)
        for version in range(1, 21):
            if len(migrations.get(version, [])) != 1:
                problems.append(f'Expected exactly one packaged SQL V{version}')
        v12 = migrations.get(12, [])
        if len(v12) == 1 and '/vendor/mariadb/' not in v12[0]:
            problems.append('SQL V12 must remain in the MariaDB vendor location')
        for version, expected in CHECKSUMS.items():
            locations = migrations.get(version, [])
            if len(locations) == 1 and checksum(jar.read(locations[0])) != expected:
                problems.append(f'Applied SQL V{version} checksum changed')
        controller = PREFIX + REQUIRED[0]
        if controller in names:
            bytecode = jar.read(controller)
            for route in (b'/scope-settings', b'/scope-catalog'):
                if route not in bytecode:
                    problems.append('Missing controller mapping ' + route.decode())
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    args = parser.parse_args()
    try:
        problems = verify(args.jar)
        if problems:
            print('\n'.join(problems), file=sys.stderr)
            return 1
        print('Profit scope recovery artifact: PASS')
        print('JAR SHA-256: ' + hashlib.sha256(args.jar.read_bytes()).hexdigest())
        print('V12/V19/V20 SQL checksums match; live acceptance is still required.')
        return 0
    except (OSError, BadZipFile, UnicodeError) as error:
        print('Cannot inspect JAR: ' + type(error).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
