# Load test results

Written for whoever needs to judge whether the product will perform. These numbers come from one laptop, so they
show where the design holds and where it does not. They are not evidence of production performance.

## Summary

| Requirement | Target | Measured | Result |
|---|---|---|---|
| NFR-07 ingest | 1M lines/hour per tenant | 4.84M lines/hour | Met |
| NFR-07 full return recompute on 5M lines | Under 10 minutes | 10.1 s for the return, 294 s with every reconciliation check | Met |
| NFR-07 drill-down on 5M lines | p95 under 2 s | **p95 6.1 s** | **Not met** |
| NFR-07 UI response | p95 under 1.5 s | Return API p95 26 ms on 5M lines. Period screens p95 465 ms and firm-wide screens p95 669 ms with 500 people working. | Met for the API once warmed up. Browser time was not measured. |
| NFR-09 scale | 5,000 entities, 500 concurrent users | No failed requests in any pass | Met on this machine, within the limits below |

## Setup

- Apple M1 Pro with 10 cores and 32 GB of memory.
- PostgreSQL 16 in Docker, in a VM with 10 CPUs and 7.7 GB, on default settings (`shared_buffers` 128 MB, `work_mem` 4 MB).
- Test JVM with a 6 GB heap.
- Run on 6 and 7 November 2026 with this command:

```bash
./mvnw -pl backend -am test -Dgroups=load -DexcludedGroups= -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=LoadTest -DargLine="-Xmx6g -Dload.ingestRows=1000000 -Dload.ledgerLines=5000000"
```

The test fails on a failed request or a rejected row. It prints latencies and does not check them against the targets,
so a passing build does not mean the targets were met. Read the figures.

## Ingestion

| Rows | Loaded | Rejected | Time | Rate |
|---|---|---|---|---|
| 1,000,000 | 1,000,000 | 0 | 744 s | 4.84M lines/hour |

The rate was 5.15M lines/hour before the drill-down index was added, so that index cost about 6%, going by one run of each.

## A period with 5 million lines

| Measure | p50 | p95 | Max |
|---|---|---|---|
| Return recompute | 10.1 s, one run | | |
| Recompute with every reconciliation check | 294 s, one run | | |
| Open the return (API) | 11 ms | 26 ms | |
| Drill-down, before the index and query rewrite | 8,673 ms | 8,924 ms | |
| Drill-down, straight after the load | 2,358 ms | 6,114 ms | 8,956 ms |
| Drill-down, after `VACUUM` | 2,429 ms | 6,149 ms | 6,434 ms |

**Drill-down misses its target at this size.** The index and query rewrite in migration V10 cut the typical wait from about
8.7 s to 2.4 s, but the p95 is still about three times the target.

In a scratch database, using the same SQL with literal values, a page took about 6 ms and a count took about 1 s once vacuumed. The
real run through the API did not match that, and vacuuming made no difference to it. The cause has not been found.
The next step is to capture the plans the application actually gets, with bind parameters and row-level security in place.

All 5 million lines are in one period of one client, which is an extreme case. It has not been measured at more ordinary sizes.

## 5,000 entities and 500 people

Each person makes 6 requests. Nine in ten read one period (return, exceptions and workflow), and one in ten reads the firm-wide
portfolio and deadlines. That gives 3,000 requests in each pass.

| Pass | Requests/s | Failures | Period screens p50 / p95 / max | Firm-wide screens p50 / p95 / max |
|---|---|---|---|---|
| Straight after a start, pauses of about 5 s | 62 | 0 | 10 / 6,296 / 7,876 ms | 121 / 6,331 / 7,934 ms |
| Warmed up, pauses of about 5 s | 68 | 0 | 9 / 465 / 1,111 ms | 123 / 669 / 1,218 ms |
| Warmed up, all at once with no pauses | 330 | 0 | 1,218 / 3,054 / 5,744 ms | 1,246 / 2,904 / 4,494 ms |

- **After a restart,** the JVM is slow for a few seconds. In the first pass, 491 of the 500 requests over 1.5 s started in the first
  5 seconds. Plan restarts away from busy periods, and warm a new instance before it takes traffic.
- **With pauses,** no request took over 1.5 s once warmed up.
- **The last pass** is harder than the requirement, because everyone is waiting on the server at once. It shows this machine tops out at about 330 requests a second.

## What these tests do not cover

- **No real network.** Requests go through Spring MVC in the same JVM, so there is no HTTP, TLS or load balancer. The load generator also shares its CPU with the application.
- **No real login.** Requests carry a test token, so Keycloak is not exercised.
- **No browser.** The UI target is measured at the API, so rendering is not included.
- **Simplified data.** The 5,000 entities have one period each with a small stored return, so large returns at this scale have not been measured.
- **Reads only at scale.** The 500 people only read. Nobody is loading data or recomputing while they work.
- **One machine.** The application and database share a laptop, and PostgreSQL has not been tuned.
