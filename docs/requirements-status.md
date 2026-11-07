# Status against the requirements

Written for whoever needs to know what is actually built. "Done" means built and covered by automated
tests on synthetic data. It does not mean validated by the tax team or used with real clients. Every tax
figure is in an unsigned rule pack, so every return, Form 211 and refund pack is still watermarked.

All 58 requirements now have code behind them. What stands between that and real use is at the end.

## The 35 Must requirements

| ID | Requirement | Status | Notes and limits |
|---|---|---|---|
| ONB-01 | Legal entity, TRN, tax group | Partial | Format check only: no published FTA checksum was found. The validator is pluggable. |
| ONB-02 | Establishments by emirate | Done | |
| ONB-03 | Frequency, stagger, calendar, due date | Done | The stagger follows from the first period's start month. Weekend rollover is off (tax team question 1), and public holidays are not modelled. |
| ONB-04 | Versioned tax code and GL mapping | Done | Also matches on supplier TRN for blocked input. |
| ONB-05 | Tax agent and signatories | Done | |
| ING-01 | E-invoice platform events | Done against the simulator | The real platform is not connected. Contract in `contracts/`. |
| ING-03 | ERP via REST, SFTP, CSV, XLSX | Done | Uploads are read into memory. The SFTP path is covered by a test of the poller; see the load test notes for the container check. |
| ING-05 | Customs declarations | Done | |
| ING-07 | Validation, duplicates, idempotent reload, lineage | Done | |
| ING-08 | FX at the Central Bank rate per line | Partial | Rates load by upload, or from a feed URL if one is configured. No public Central Bank API was found, so no source is built in (question 28). A missing rate holds the row and blocks approval. |
| TXC-01 | Canonical treatment per line | Done | |
| TXC-02 | Place of supply and designated zones | Done, rules draft | Rules are advisory where a tax code is mapped: a disagreement raises a blocking exception. |
| TXC-03 | Domestic reverse charge | Done, rules draft | Mapped codes apply DRC. Descriptions only raise a warning. Seller-side box is unconfirmed. |
| TXC-04 | Blocked input by GL, vendor, description | Done, rules draft | |
| TXC-07 | Bulk reclassification with reason and audit | Done | Up to 5,000 lines at a time. |
| RET-01 | VAT201 from metadata formulas | Done | Golden returns match on every box. |
| RET-02 | Drill-down to lines, documents, UUIDs | Done | |
| RET-03 | Recompute on change, numbered versions, diffs | Done | |
| RET-04 | Lock on approval, changes only by adjustment or disclosure | Done | |
| RET-06 | Tax group consolidation | Done | |
| ADJ-01 | Six-month reversal and reinstatement | Done | Pro rata for part payments (tax team question 8). Payment comes from ERP status or a matched bank line. |
| ADJ-05 | Deemed supplies and journals with evidence | Done | |
| REC-01 | Rule set on demand and on every load | Done | All 14 checks. R08 is limited: see below. |
| REC-02 | Exception queue | Done | |
| REC-03 | Block approval on blocking exceptions | Done | Enforced on the server. |
| REC-04 | Reconciliation certificate | Done | |
| WF-01 | Maker-checker and partner threshold | Done | Built on PostgreSQL, not Temporal or Camunda. |
| WF-02 | Comment threads | Done | |
| WF-03 | Deadline dashboard and alerts | Done | Email and in-app. |
| FIL-01 | Return pack | Done | PDF, XLSX and JSON, with the PDF and XLSX in English and Arabic. The Arabic is a draft. |
| FIL-02 | Submission reference and acknowledgement | Done | |
| FIL-03 | Payment tracking and bank match | Done | A statement line naming the FTA GIBAN with the amount due settles the return by itself. Anything less waits for a person. One bank line cannot settle two payments. |
| COR-01 | Tax difference and routing | Done | |
| AUD-02 | Evidence vault with WORM retention | Done | GOVERNANCE lock mode locally. Use COMPLIANCE in production. |
| PRC-01 | Portfolio dashboard | Done | |

**Check R08** warns from the format and a local register that a person maintains, because no public FTA
verification API was found. A live Peppol lookup is built, using the network's DNS records, but it is off by
default and has not been run against the real network: DNS was not reachable from the build machine.

## The 23 Should and Could requirements

| ID | Requirement | Priority | Status | Notes and limits |
|---|---|---|---|---|
| ONB-06 | Opening positions | S | Done | Unpaid supplier invoices, capital assets and apportionment figures all load from templates. |
| ONB-07 | Designated zone flag | S | Done | |
| ING-02 | E-invoices from other ASPs | S | Done | UBL XML or ZIP, by upload or API. Corner 5 status is not in the document, so it stays pending until given. Parsed from the published PINT AE structure and tested on hand-written samples, not real files from an ASP. |
| ING-04 | Native ERP connectors | C | Built, unverified | A REST pull engine with stored mappings, tested against a stub server. SAP, Oracle Fusion, Dynamics 365 and Zoho Books have REST mappings. NetSuite, Odoo and Tally use their own export files. **None has run against a live system**, and field names come from public documentation. SAP IDoc is not covered. |
| ING-06 | Bank statements | S | Done | MT940 and CAMT.053. Tested on hand-written samples, so real banks' variations are untested. |
| TXC-05 | Zero-rating evidence | S | Done | Checks T07 and T08. Goods only (question 22). |
| TXC-06 | Suggestions with confidence | S | Done | From this client's own history, keywords and rules. No machine learning. A person accepts each one, and each is logged with inputs and engine version. |
| RET-05 | Profit margin scheme | S | Done | Indicator and schedule in the pack (question 21). |
| ADJ-02 | Bad debt relief | S | Done | Four conditions checked, a second person approves, and a later recovery pays it back. |
| ADJ-03 | Apportionment | S | Done | Input-based now and output-based for tax years from October 2027, plus the annual adjustment (question 17). |
| ADJ-04 | Capital assets scheme | C | Done | Register, yearly intervals, disposal. Check T10 warns when one is due. |
| WF-04 | Client e-signature | S | Done | A typed name, consent and the signed-in identity, bound to the exact figures. Not a qualified signature from a trust service (question 26). |
| FIL-04 | Direct submission | C | Slot only | Stays unavailable, as the spec says, until the FTA publishes an API. No automation of EmaraTax's web pages exists or is planned. |
| COR-02 | Form 211 pack | S | Done | Original and corrected figures, deadline, approval by a second person, then submission and settlement. |
| COR-03 | Penalty calculator | S | Done | Effective-dated. Reproduces the published worked examples. Says it cannot estimate before 14 April 2026, and never guesses. Firm-only: external auditors cannot see it. |
| COR-04 | Refund pack | S | Done | Time limit check, pack, then submission, decision and receipt. |
| AUD-01 | FTA Audit File | S | Done | Tables and fields from the FTA's own software requirements document (October 2017). Section markers and some codes are unconfirmed, and held in the rule pack (question 18). Its general ledger needs journal lines loaded through the new template. |
| AUD-03 | Audit case tracker | C | Done | Requests, deadlines, reminders, a checked bundle, and legal holds placed automatically. |
| ANL-01 | Trends | S | Done | |
| ANL-02 | Anomaly flags | S | Done | Thresholds are in the rule pack. |
| ANL-03 | Registration threshold | C | Done | Figures are entered per month, since these clients have no data here (question 24). |
| PRC-02 | Staff and workload | S | Done | An assignment shows responsibility and grants no access. |
| PRC-03 | Billing and time webhooks | C | Done | Signed, retried, and driven from the audit log. Tested against a local stub. |

## Core journeys

All five now run end to end, and are covered by browser tests against the real services.

| Journey | Before | Now |
|---|---|---|
| 1. Onboarding | Done | Done, plus opening assets and ratios |
| 2. Period close | Done | Done |
| 3. Error found after filing | Routed only | Form 211 pack, penalty exposure, both versions kept |
| 4. FTA audit | Not possible | Case, FAF, evidence bundle, deadlines |
| 5. Refund | Flagged only | Pack, time limit, status to receipt |

## Non-functional requirements

| ID | Area | Status |
|---|---|---|
| NFR-01 | UAE data residency | Not done: a hosting decision. Nothing in the code stops a UAE deployment. |
| NFR-02 | Tenant isolation | Row-level security, and per-tenant data keys for everything in object storage, are done and tested. Dedicated databases for large clients are not done. |
| NFR-03 | Encryption, secrets | Application side done: AES-256-GCM per tenant, with each key wrapped by a key service, and webhook and connector secrets encrypted. The local key service refuses to start under the prod profile. The AWS KMS adapter is written but **has not run against KMS**. TLS, disk encryption and a real vault need the production platform. |
| NFR-04 | SSO, MFA, RBAC | OIDC login and per-entity RBAC done. TOTP is required for new realm users; seeded demo users are exempt. SAML works through Keycloak but is untested. |
| NFR-05 | Audit trail | Done. Hash chain with tamper tests, anchored into locked storage at each approval. |
| NFR-06 | Retention | Done for what code can do. Object Lock and legal holds cover files, the database role cannot delete tax records at all, and a report shows what has aged out. There is deliberately no purge: deleting tax records needs a policy decision. |
| NFR-07 | Performance | Measured on one laptop: see `load-test-results.md`. Ingest, recompute and API response meet their targets there. **Drill-down on a 5M line period does not:** p95 6.1 s against 2 s. Not evidence of production performance. |
| NFR-08 | Availability | Not done: a production matter |
| NFR-09 | Scale | Tested at the design point on one laptop: see `load-test-results.md`. Several workers can run at once: recomputes take the period lock, reminders claim their row before sending, and webhook deliveries are taken on a lease. |
| NFR-10 | Regulatory agility | Done. Tested by changing the form through a new pack with no code. |
| NFR-11 | Localisation | English and Arabic right-to-left, including server errors, notifications and the PDF and XLSX packs. All the Arabic is an unreviewed draft and needs a native review, tax terms above all. Free-form server detail, such as reconciliation messages, stays in English beside the translated heading. |
| NFR-12 | Accessibility | Automated checks pass on all main screens in both languages. A manual audit is still needed. |
| NFR-13 | Certification | Not done |
| NFR-14 | Observability | Metrics and alert rules done. Traces export over OTLP, and have been seen arriving in a local Jaeger. Production needs its own collector. Per-tenant dashboards are not done. |
| NFR-15 | AI use | Applies only to suggestions, which are rule-based, logged, and never change a value without a person. No model is used. |

## Actions with no screen yet

Everything in the core journeys has a screen. These work through the API only:

- **Adding a person to the firm** (`POST /api/users`). They are first created in the identity provider, and their
  id there is given to this call. Giving an existing person access to a client has a screen.
- **Asking the e-invoice platform to resend events** (`POST /api/entities/{id}/einvoices/backfill`), for a new client or after an outage.
- **The REST route for ERP rows** (`POST /api/entities/{id}/ingest/{kind}/rows`), which is for systems and not people.
- **The history of one ledger line** (`GET /api/lines/{id}/history`) and **the reconciliation certificate on its own** (`GET /api/periods/{id}/certificate`). The certificate is also in the return pack.

## Deviations from the spec's architecture

These are deliberate, and nothing in this work changes them.

- **Modular monolith instead of separate services.** Same boundaries, enforced by tests.
- **PostgreSQL workflow instead of Temporal or Camunda.** Approvals are simple transitions and a daily job.
- **No ClickHouse.** Analytics are per entity and small, so PostgreSQL serves them.
- **Ledger values come from the ERP**, with e-invoices as the reconciling set, within the AED 1 tolerance per
  invoice. The certificate shows the total variance. Tax team question 4 asks whether this is right.

## What code cannot finish

- **Tax team sign-off** of rule pack AE-VAT 1.1. Until then every pack is watermarked, which is correct. The figures in sections added for this work come from secondary sources: see questions 14 to 28.
- **The Phase 1 exit gate:** 5 pilot clients reconciling to their manually prepared returns.
- **Answers from the FTA:** a VAT201 API or pre-populated returns, TRN check digit (ONB-01 still checks format only), and any newer FAF specification.
- **The real ASP platform** (ING-01 is still tested against the simulator only), and first contact with live ERPs and real bank files.
- **Hosting:** UAE region, key service, availability, backups, ISO 27001 and SOC 2.
- **A native Arabic review and a manual accessibility audit.**
