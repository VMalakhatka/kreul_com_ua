# KREUL Java API

Spring Boot service between WordPress/WooCommerce and the legacy Folio system on
MS SQL. The service also publishes read models and operational state to the
WordPress MariaDB database.

## Start Here

- [Java documentation map](docs/README.md) - API contracts, business rules,
  database catalog and experiments.
- `docs/BACKEND_GUIDE.md` in the WordPress repository - the canonical
  cross-system backend guide for PHP, Java, Folio, MariaDB and external services.
- `docs/JAVA_DOCKER_RUNTIME.md` in the WordPress repository - local Docker,
  environment model, production deploy, health and rollback.
- `docs/BOOTSTRAP_AND_RECOVERY.md` in the WordPress repository - start from zero,
  platform migration and disaster recovery order.

The WordPress repository is the human documentation entry point for the whole
Lavka/KREUL platform. This repository owns the Java implementation and exact API
contracts.

## Runtime

- Java 17, Spring Boot 3.2.3 and Maven.
- jTDS 1.3.1 for the legacy SQL Server 2000 installation.
- MariaDB JDBC and Flyway for WordPress-side projections.
- Docker image/container `kreul-api`, application port `8080`.
- Canonical health endpoint: `GET /healthz`.

Do not copy values from `.env*`, `env-local.sh` or `application.properties` into
documentation or shared logs. Document key names, safe defaults and endpoint
classes only.

## Safe Local Sequence

1. Select direct Java, Docker Desktop or production-like mode; their loopback and
   network semantics differ.
2. Compare the required environment key set without printing values.
3. Keep Folio/Woo write flags and schedulers disabled until explicitly needed.
4. Run focused tests and package with Java 17.
5. Start the service and verify `/healthz`.
6. Verify one read-only request for each configured dependency.
7. Connect the WordPress server-side proxy only after the Java checks pass.

Docker commands, production preflight and rollback live in the canonical runtime
runbook named above. A successful HTTP response alone is not proof of a correct
business result.

## Ownership Boundaries

- Controllers, DTOs, services, DAOs, Flyway migrations and API contracts: this
  repository.
- WordPress proxy, Woo workflows and operator UI: the WordPress repository.
- Folio table/procedure semantics and write safety: the project skill
  `.agents/skills/work-with-folio-mssql` plus the live schema.
- Image/container/env/health/deploy: `$build-java-docker-runtime`.
- Host, VM, Docker daemon, disks and network: `$server-lavka`.

MariaDB and Folio/MS SQL do not share a transaction. Cross-system workflows must
use idempotency, explicit status and a recoverable partial-failure strategy.

## Documentation Rule

Every Java change must update documentation in the same change:

- endpoint, request, response or status -> the exact file in `docs/api/`;
- Folio business invariant or schema evidence -> `docs/business/`,
  `docs/00_DATABASE_CATALOG.md` or the Folio skill reference;
- migration or projection ownership -> [Java documentation map](docs/README.md)
  and the owning API contract;
- Docker/env/health/deploy -> WordPress `docs/JAVA_DOCKER_RUNTIME.md`;
- cross-system ownership, startup or recovery -> WordPress
  `docs/BACKEND_GUIDE.md` or `docs/BOOTSTRAP_AND_RECOVERY.md`.

Unverified behavior belongs in a clearly marked gap, not in a confident runbook.

## Documentation Check

Run before handing off a change:

```bash
python3 scripts/check-documentation.py --working-tree
```

The versioned pre-commit hook uses `--staged`; enable it once with
`git config core.hooksPath .githooks`. Pull requests run the same impact rules in
GitHub Actions. Repository settings must make the check required if it should block
merges.
