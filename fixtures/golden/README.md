# Golden returns

Five synthetic entities with expected VAT201 figures worked out by hand from the rows, before the
engine ran. Boxes not listed in `expected.json` must be zero. The tests load these through the real
ingestion path and compare every box.

These prove the engine does what I intended, not that I read the law correctly. The expected values
are mine, so they are not independent evidence. The tax team should check them (question 10 in
`docs/tax-team-questions.md`), and the real exit gate is 5 pilot clients reconciling to their
manually prepared returns.

| Fixture | Covers |
|---|---|
| g1-standalone-monthly | Credit note, B2C, export, blocked input by GL |
| g2-standalone-quarterly | All seven emirates, USD conversion, exempt, imported services under reverse charge |
| g3-tax-group | Consolidation and per-member contribution |
| g4-importer-drc | Customs boxes 6 and 7, DRC purchases, an import in AP that must not count twice |
| g5-designated-zone | Goods within zones outside scope, export, exempt, 50% recovery, blocked by description |

Every entity also supplies GL balances so check R06 can pass.
