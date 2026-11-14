# UAE VAT Compliance and Filing Tool

Turns e-invoice data and ERP transactions into a reconciled, approved VAT201 return with a defensible
audit trail. A tax agent then submits the return on EmaraTax: the tool prepares and assists, and does
not file. All 58 requirements in the requirements document have code behind them, running locally on
synthetic data.

**This is not production-ready.** See [What is not done](#what-is-not-done). In particular, the tax rules
are an unsigned draft, and every return pack is watermarked `DRAFT RULE PACK: NOT FOR FILING` until
your tax team signs the rule pack off.

## Run it

Needs Docker, Java 21 and Node 20.

```bash
./scripts/dev-up.sh
```

Then open <http://localhost:5183> and sign in. The password for all demo users is `demo-pass-1`.

| User | Role |
|---|---|
| `admin` | Firm admin: onboards clients, signs off rule packs, reads the audit log |
| `preparer` | Loads data, fixes classifications, submits for review |
| `reviewer` | Tax reviewer: signs off, waives, proposes adjustments, opens corrections, records filing |
| `partner` | Engagement partner: signs off above the risk threshold |
| `approver` | Client CFO: approves and locks |
| `auditor` | External auditor: reads locked periods only |

### Demo data

On first start in the dev profile the backend builds a year of history for five fictional clients, which takes about
five minutes. It goes through the real services as the people above, so every return was reconciled, signed off in turn,
packed and filed, and every bank line, e-invoice and audit event is the kind the product would hold. Three more firm staff,
the clients' own approvers and an external auditor exist as users too, but cannot sign in. Each client's last month is
left at a different stage, so every screen has something on it:

| Client | What its year shows | Last month's return |
|---|---|---|
| Al Noor Home Furnishings Trading LLC | Monthly. E-invoicing pilot since July, exports, antique carpets under the margin scheme, imports through customs, a refund, bad debt relief and a later recovery, the six-month rule, a voluntary disclosure settled, an open FTA audit | With the reviewer. A waived R06 means the partner signs too, then the client approves by e-signature |
| Falcon Logistics Holding LLC | A tax group with two members. E-invoicing, a refund rejected and then paid, a disclosure drafted, the truck fleet under the capital assets scheme | Open, with work in it: a missing e-invoice to ask the platform for, a fuzzy match to accept, rows rejected at load, a new SAP tax code with suggestions, the six-month rule. The month before is filed and only half paid |
| Desert Rose Real Estate LLC | Quarterly. Exempt residential leases, apportionment with the annual adjustment, the tower under the capital assets scheme, records kept 15 years, a refund approved, a closed FTA review | Open and nearly ready: a deemed supply waits for the reviewer |
| Zarina Jewellery & Bullion Trading LLC | Gold bought under the domestic reverse charge, bullion imports, tourist refunds, exports, pre-owned jewellery under the margin scheme, a refund received, a closed audit | Approved, pack generated, waiting to be filed on EmaraTax |
| Jebel Ali Marine Supplies FZCO | A designated zone company. Out-of-scope zone sales, refunds at every stage, a disclosure submitted, an open refund audit | Blocked by an export past its 90 days and an invoice waiting for a rupee rate. Pull the rate feed on the Practice page to free it |

Every name, TRN, figure and document is made up, and the files in the evidence vault say so on their face. Dates follow
the day the demo was built, so last month is always the one being closed. To build it again from an empty database:

```bash
./scripts/reset-demo.sh   # backs up the current database to .data/backups first
```

In development a stub on port 18097 stands in for the outside systems the settings point at: the billing system that
receives webhooks, the clients' ERPs, a Central Bank rate feed, and the e-invoicing platform's backfill. The browser test
`e2e/demo-tour.spec.ts` checks that every section of every demo client has something to show. The golden entities in
`fixtures/golden` are for the tests and are no longer loaded into the dev database.

| Service | Address |
|---|---|
| App | <http://localhost:5183> |
| API | <http://localhost:18090> |
| Keycloak admin (admin / admin) | <http://localhost:18180> |
| Alert emails (Mailpit) | <http://localhost:18125> |
| Object storage console (vatminio / vatminio-secret) | <http://localhost:19101> |
| Traces (Jaeger) | <http://localhost:16686> |
| Health and metrics, for Prometheus (local only, no token) | <http://localhost:18091/actuator/prometheus> |

Ports are chosen to avoid others in use on the development machine, and the Compose project is
named `uaevat`, so it does not touch other projects' containers.

### Rule packs and client rules

Every client follows the firm's rule pack: the tax rules and the VAT201 form, held as data. On the Rule packs page an
admin opens a version to read it section by section, along with its source and the notes and open questions in it. To
change it, the admin edits a copy. A check lists every rule the copy changes before it is saved. Saving makes a new
draft version, in force from its effective date, and its return packs carry the watermark until the tax team signs it
off. A version saved by mistake can be retired, and the one before applies again.

Client rules sit on top of the pack for one client, in the client's Rules card. They can add classification rules and
review patterns, switch off a firm rule with a reason, and move review tolerances and analytics thresholds. They cannot
change rates, the form, deadlines or penalties. Each change is a dated version with a reason, signed off like the pack,
and each line records the version that classified it. Neither kind of change touches lines already loaded: they keep
their treatment, and new rules apply to data loaded afterwards and to the next reconciliation.

In the demo, the firm has signed off the shipped pack and an edit of its own, which blocks golf and club memberships
as entertainment. Every client has signed-off rules of its own, and Falcon has a draft for next month, waiting for
sign-off, that switches off the diesel check.

### Feed in e-invoices

The real ASP platform is not connected. The simulator publishes events to Kafka from an AR or AP file,
and can inject the faults reconciliation exists to catch.

```bash
java -jar asp-simulator/target/asp-simulator-0.1.0-SNAPSHOT-exec.jar \
  --tenant=11111111-1111-4111-8111-111111111111 --trn=100000000000101 \
  --issued=fixtures/golden/g1-standalone-monthly/ar.csv \
  --faults=missing:1,mismatch:1,rejected:1,duplicate:1,outOfOrder:1,corner5Failed:1
```

The event contract for the platform team is in [contracts/einvoice-events](contracts/einvoice-events).

### Load files by SFTP

Clients drop files at `inbox/<tenantId>/<entityId>/<KIND>/file.csv`, uploading as `file.csv.part` and
renaming when complete. Processed files move to `processed/`, and failures to `failed/` with a note.

## Test it

```bash
./mvnw verify                        # backend and simulator, against real PostgreSQL, Kafka and object storage
cd frontend && npm test              # front-end unit tests, including translation coverage
cd frontend && npx playwright test   # end to end in a browser (needs the app running)
```

Load tests run on request:

```bash
./mvnw -pl backend -am test -Dgroups=load -DexcludedGroups= -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=LoadTest -DargLine="-Xmx6g -Dload.ingestRows=1000000 -Dload.ledgerLines=5000000"
```

Results so far are in [docs/load-test-results.md](docs/load-test-results.md).

## How it is built

A modular monolith on Java 21 and Spring Boot, one jar with `api` and `worker` profiles, with the React
front end talking to it over REST. ArchUnit keeps the modules free of cycles so they can be split later.

```
backend/src/main/java/ae/vattool/
  platform        tenancy, security, audit chain, WORM storage, per-tenant keys, generated documents
  rules           rule pack store, classifier
  masterdata      entities, establishments, calendar, mapping, parties
  ledger          the canonical tax ledger, and the only way to write to it
  ingestion       templates, row processing, e-invoice bridge, UBL import, ERP connectors, FX feed, SFTP
  returns         VAT201 engine, versions, drill-down
  reconciliation  checks R01 to R14 plus data and tax checks, matching, exceptions, certificate
  workflow        maker-checker, sign-offs, comments, deadline alerts
  evidence        evidence vault
  adjustments     six-month rule, journals, apportionment, bad debt, capital assets, corrections
  corrections     Form 211, penalty exposure, refund claims
  auditcase       FTA Audit File, audit cases and bundles
  banking         MT940 and CAMT.053 statements, and matching supplier invoices
  classification  suggestions, export evidence
  filing          return pack, filing and payment, bank payment matching
  analytics       trends, anomaly flags, registration threshold
  practice        staff assignment and workload, webhooks
  portfolio       firm-wide dashboard, deadlines, retention report
rulepacks/        tax rules and the VAT201 form, as data
contracts/        proposed event contract with the ASP platform
asp-simulator/    stands in for the ASP platform
fixtures/golden/  five synthetic entities with hand-worked expected returns
docs/             status against the requirements, tax team questions, load test results, production readiness
```

### The rules that matter when changing this code

- **Tenancy.** The app connects as `vat_app`, which cannot bypass row-level security. The tenant is set per
  transaction, so any query outside a `@Transactional` method sees nothing. Calls between methods of one
  class skip the Spring proxy, so a controller must load through another bean. Tests guard the schema:
  a new table with tenant data and no policy fails the build.
- **Entity checks are yours.** Row-level security separates tenants, not entities within one. Check that an
  object belongs to the entity in the URL.
- **The ledger is append-only.** Write through `Ledger` only. Lines are never changed: a correction appends
  a revision and stamps `superseded_at`, which a trigger enforces.
- **Lock order.** Take the period row first, then the recompute request row, and write to the audit log
  last. A different order can deadlock a data load against the recompute job.
- **Tax logic is data.** Put rules and form changes in a rule pack, not code: edit a copy on the Rule packs page,
  or upload one through the API, as a new version with an effective date. What applies to one client only goes in
  its client rules, never in a copy of the pack.
- **Nothing disappears silently.** A rejected, held or unclassified row raises a blocking exception.

## What is not done

Needed before real use, and not code that could be written here:

- Tax team sign-off on rule pack AE-VAT 1.1 and the golden returns: [docs/tax-team-questions.md](docs/tax-team-questions.md)
- Answers from the FTA: VAT201 API or pre-populated returns, TRN check digit and verification, any newer FAF specification
- Connecting the real ASP platform, after its team reviews the event contract, and first contact with live ERPs and real bank files
- The exit gate: 5 pilot clients reconciling to their manually prepared returns
- UAE hosting, a production key service, the availability targets, backups, and ISO 27001 and SOC 2: the decisions are in [docs/production-readiness.md](docs/production-readiness.md)
- A native Arabic review of the draft translations, especially tax terms
- A manual accessibility audit: the automated checks catch only part of WCAG 2.1 AA

Known limits are listed per requirement in [docs/requirements-status.md](docs/requirements-status.md). The ones
most likely to matter: the ERP connector mappings have never met a live system, and only CSV uploads and the FAF
are streamed. A workbook, a download or an audit bundle is still held in memory, so very large files should come
as CSV.

For production: run with the `prod` profile and set `app.crypto.kms` to a real key service, which that profile
enforces. Set `app.storage.lock-mode` to `COMPLIANCE`, replace the demo realm and its users, and leave `app.seed`
off. New Keycloak users must enrol TOTP, but the seeded demo users are exempt.

A database from before rule pack 1.1 keeps its older pack until an admin installs the new one on the Rule packs
page. It installs as a draft, so any sign-off must be given again.
