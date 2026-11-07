# Production readiness

Written for whoever decides how and where this product runs: the product owner, the infrastructure lead and the
security lead. It lists what the code already does, what it cannot do for you, and the decisions needed before
real client data goes in. The recommendations are starting points, and none has been tried on a production platform.

Run everything below only after the tax team has signed off a rule pack. Until then every pack is watermarked
as not for filing, whatever the hosting looks like.

## Decisions needed

| # | Decision | Requirement | Who decides | Recommendation |
|---|---|---|---|---|
| 1 | Which UAE hosting provider and region | NFR-01 | Product owner, infrastructure lead | A UAE region from a provider with a managed key service, Object Lock storage and managed PostgreSQL. The only key service adapter written so far is for AWS KMS, so AWS's UAE region is the shortest path. Azure's UAE regions would need a small Key Vault adapter. Check that each service below is offered in the chosen region, and that the choice fits the ASP accreditation conditions. |
| 2 | Which key service, and whether keys need an HSM | NFR-02, NFR-03 | Security lead | A managed key service, with one master key per environment. Move to an HSM-backed key only if a client or the accreditation asks for it, as it changes one setting and not the code. |
| 3 | Who owns availability and on-call | NFR-08 | Product owner | Name the on-call rota before the first pilot, with cover in the 7 days before each 28th. |
| 4 | Whether to certify, and when | NFR-13 | Product owner, security lead | Start ISO 27001 at the same time as the hosting work, because the spec has it in scope from launch. SOC 2 can follow if clients ask for it. |
| 5 | Whether old tax records are ever deleted | NFR-06 | Tax team, legal | Do not delete anything until the tax team and legal agree a policy. The database role cannot delete tax records at all today, and that is deliberate. |
| 6 | Which clients get a dedicated database | NFR-02 | Product owner | None at launch. Row-level security is tested. Offer it only if a large client asks: it needs a tenant-routing layer that does not exist yet. |

## What each requirement needs

### NFR-01: UAE data residency

The spec says all production data and backups stay in UAE regions, with no processing elsewhere without written
client consent.

- **The code** holds no hosting assumption, and every address is configuration.
- **Still needed:** the deployment itself, and a check of what else touches data. That means email delivery, the
  identity provider, log and trace collectors, backups, and any support tooling. Each must be in the UAE or be
  shown to carry no client data.
- **Watch:** trace spans can carry URLs and entity ids. Keep the collector in the region too.

### NFR-02 and NFR-03: isolation, encryption and secrets

**Done in the code:**

- Row-level security on every tenant table, tested for cross-tenant reads.
- Each tenant's files are encrypted with its own AES-256-GCM key, and that key is stored only wrapped by the key service.
- Webhook and connector secrets are encrypted with the same tenant key.
- The local development key service refuses to start under the `prod` profile.

**Not verified:** the AWS KMS adapter has never run against real KMS. Test it in the chosen region first:
create a tenant, upload a file, restart the service, and read the file back.

**Still needed:**

- TLS 1.2 or later at every entry point, and between the service and its database, storage and Kafka.
- Disk encryption on the database and on Kafka.
- A vault for database, storage and identity provider credentials. Today these are configuration values.
- Replacing the demo realm and users, and leaving `app.seed` off.
- A plan for files stored before encryption existed, which the code reads as they are. A production start has none.

### NFR-06: retention

**Done in the code:**

- WORM storage with Object Lock for raw files, evidence, packs and snapshots.
- Legal holds, placed automatically when an audit case opens.
- A retention report on the Practice page, with a flag for 15-year real estate records.
- `DELETE` is revoked from the application role on tax records.

**Still needed:** set `app.storage.lock-mode` to `COMPLIANCE` in production, because governance mode can be bypassed by
an administrator. Decide decision 5. Nothing purges records past their retention date.

### NFR-07 and NFR-09: performance and scale

The numbers in [load-test-results.md](load-test-results.md) come from one laptop. Repeat them on production-sized
hardware before relying on them.

- The API and workers can run apart with the `api` and `worker` profiles, and several of each can run at once.
- Recomputes take the period lock first, so a second worker finds nothing to do. Deadline and audit reminders claim
  their row before sending. Webhook deliveries are taken on a short lease.
- Connector pulls and the FX feed can run twice if two workers fire together. Reloads are idempotent, so nothing
  doubles, but the source system is asked twice. Run the scheduled pulls on one worker if that matters to a source.
- The SFTP poller expects one worker per inbox directory.
- Uploads, the FTA Audit File and audit bundles are held in memory. Size the heap for the largest file you accept,
  or do the streaming work first.
- Drill-down on a period with 5 million lines still misses its target: p95 6.1 s against 2 s. The cause has not been found.
- PostgreSQL in Docker needs more than the default 64 MB of shared memory. Without it, `VACUUM` and parallel queries fail on
  large tables. `docker-compose.yml` sets 1 GB.

### NFR-08: availability

The target is 99.9% monthly, 99.95% in the 7 days before each 28th, an RPO of 15 minutes and an RTO of 4 hours.

| Need | How |
|---|---|
| RPO 15 minutes | PostgreSQL point-in-time recovery with continuous WAL archiving. Object storage replication with versioning. Keep a few days of events in Kafka, because the platform can also resend them through the backfill route. |
| RTO 4 hours | A written restore procedure, timed in a drill before launch and repeated. It must cover the database, the object store and the wrapped keys together. |
| 99.9% | At least two API instances across availability zones behind a load balancer using `/actuator/health/readiness`. A database with a standby. |
| 99.95% before deadlines | A change freeze in those 7 days, and scaling up ahead of them. |

**Watch:** a restored database must match the key service. If the master key is lost, every file is lost with it.
Protect it from deletion and test the restore end to end.

### NFR-13: certification

ISO 27001 and SOC 2 need policies, access reviews, supplier reviews and audit evidence over time. The code helps with
the audit trail and with access controls, but the certificate is an organisational programme.

### NFR-14: observability

- **Metrics and alert rules** exist. See `infra/` for the rules, and treat their thresholds as starting points.
- **Tracing** exports over OTLP and has been seen arriving in a local Jaeger. In production set
  `management.tracing.export.enabled` to true and point
  `management.opentelemetry.tracing.export.otlp.endpoint` at your collector. Keep the sampling at the default 10%
  unless you need more.
- **Per-tenant dashboards** do not exist yet.

## Before the first pilot client

1. Tax team sign-off on rule pack AE-VAT 1.1 and the golden returns, with answers to [the questions](tax-team-questions.md).
2. Decisions 1 to 3 above, and the deployment they lead to.
3. A KMS round trip and a timed restore drill in the chosen region.
4. A native Arabic review of the translations and packs, and a manual accessibility audit.
5. The real ASP platform connected, after its team reviews the event contract in `contracts/`.
6. A penetration test.

The Phase 1 exit gate is then 5 pilot clients whose returns match their manually prepared ones.
