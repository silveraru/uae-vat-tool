# Status against the requirements

Written for whoever needs to know what the MVP actually covers. "Done" means built and covered by
automated tests on synthetic data. It does not mean validated by the tax team or used with real clients.

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
| ING-08 | FX at the Central Bank rate per line | Partial | Rates are loaded by upload, since no feed is defined. A missing rate holds the row and blocks approval. |
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
| ADJ-01 | Six-month reversal and reinstatement | Done | Pro rata for part payments (tax team question 8). Uses ERP payment status until bank import exists. |
| ADJ-05 | Deemed supplies and journals with evidence | Done | |
| REC-01 | Rule set on demand and on every load | Done | All 14 checks. R08 is limited: see below. |
| REC-02 | Exception queue | Done | |
| REC-03 | Block approval on blocking exceptions | Done | Enforced on the server. |
| REC-04 | Reconciliation certificate | Done | |
| WF-01 | Maker-checker and partner threshold | Done | Built on PostgreSQL, not Temporal or Camunda. |
| WF-02 | Comment threads | Done | |
| WF-03 | Deadline dashboard and alerts | Done | Email and in-app. |
| FIL-01 | Return pack | Done | PDF, XLSX, JSON. PDF and XLSX are in English. |
| FIL-02 | Submission reference and acknowledgement | Done | |
| FIL-03 | Payment tracking and bank match | Partial | Compares what a person keys from an uploaded confirmation. Statement parsing is Phase 2. |
| COR-01 | Tax difference and routing | Done | Form 211 itself is not generated (COR-02 is a Should). |
| AUD-02 | Evidence vault with WORM retention | Done | GOVERNANCE lock mode locally. Use COMPLIANCE in production. |
| PRC-01 | Portfolio dashboard | Done | |

**Check R08** warns from the format and a local register that a person maintains, because no public FTA
verification API was found. The Peppol lookup is a stub that reports `NOT_CHECKED`.

## Pulled in because a Must depends on them

| ID | Part built | Why |
|---|---|---|
| ONB-06 | Opening unpaid supplier invoices only | Otherwise ADJ-01 misses invoices already unpaid at go-live |
| ONB-07 | The designated zone flag | TXC-02 needs it |
| Box 2 | A tourist refund document type in the template | The box had a source but no way in |

## Non-functional requirements

| ID | Area | Status |
|---|---|---|
| NFR-01 | UAE data residency | Not done: a hosting decision |
| NFR-02 | Tenant isolation | Row-level security done and tested. Per-tenant keys and dedicated databases are not done. |
| NFR-03 | Encryption, secrets | Not done: needs the production platform. Dev passwords are in config. |
| NFR-04 | SSO, MFA, RBAC | OIDC login and per-entity RBAC done. TOTP is required for new realm users; seeded demo users are exempt. SAML works through Keycloak but is untested. |
| NFR-05 | Audit trail | Done. Hash chain with tamper tests, anchored into locked storage at each approval. |
| NFR-06 | Retention | 5 and 15 year Object Lock and legal hold done for raw files, evidence, packs and snapshots. Database retention is not enforced by a process yet. |
| NFR-07 | Performance | Measured on one laptop: see `load-test-results.md`. Not evidence of production performance. |
| NFR-08 | Availability | Not done: a production matter |
| NFR-09 | Scale | Worker and API roles separate. Not tested at 5,000 entities or 500 users. |
| NFR-10 | Regulatory agility | Done. Tested by changing the form through a new pack with no code. |
| NFR-11 | Localisation | English and Arabic right-to-left done. Arabic is my draft and needs review. Server messages are English. |
| NFR-12 | Accessibility | Automated checks pass on all main screens in both languages. A manual audit is still needed. |
| NFR-13 | Certification | Not done |
| NFR-14 | Observability | Metrics and alert rules done. Tracing is wired but needs a collector. |
| NFR-15 | AI use | Not applicable: the MVP has no AI |

## Deviations from the spec's architecture

- **Modular monolith instead of separate services.** Same boundaries, enforced by tests.
- **PostgreSQL workflow instead of Temporal or Camunda.** Approvals are simple transitions and a daily job.
- **No ClickHouse.** The only portfolio view in the MVP is served by PostgreSQL.
- **Ledger values come from the ERP**, with e-invoices as the reconciling set, within the AED 1 tolerance per
  invoice. The certificate shows the total variance. Tax team question 4 asks whether this is right.

## Not built

The 17 Should and 6 Could requirements, apart from the parts above. That includes FAF export, Form 211
generation, the penalty calculator, refund packs, bad debt relief, automatic apportionment, the capital
assets scheme, native ERP connectors, bank statement parsing and analytics.
