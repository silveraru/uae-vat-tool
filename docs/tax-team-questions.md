# Questions for the tax team

The rule pack ships as DRAFT and every return pack is watermarked until these are settled and the
pack is signed off. Answers usually change configuration in the rule pack, not code.

| # | Question | What the build does until answered |
|---|----------|------------------------------------|
| 1 | Does a deadline on a weekend or public holiday move to the next working day? 28 Feb 2027 is a Sunday. | Due date stays on the 28th day after period end (the safe side). A setting in the rule pack turns on weekend rollover; public holidays are not modelled. |
| 2 | Which VAT201 box does the seller of domestic reverse charge goods use? I found no source for this. | Seller-side DRC lines go in no box and raise a warning. |
| 3 | Has the DRC scope grown beyond gold, diamonds, hydrocarbons and electronic devices? Secondary sources mention other precious metals and stones, and scrap metal. | Wider terms are detected and flagged for review. Treatment changes only when a tax code is mapped to DRC. |
| 4 | Should a return carry the e-invoice value or the ERP value when they differ within the AED 1 rounding tolerance? | The ledger uses ERP values. R03 limits the difference per invoice, and the reconciliation certificate shows the total variance against Corner 5. |
| 5 | Which FX rate applies on a day with no published Central Bank rate? | The line is held and a blocking exception is raised. Nothing falls back silently. |
| 6 | Is the current penalty regime Cabinet Decision 129 of 2025, effective 14 April 2026? Needed for COR-03 in Phase 2. | Not used in the MVP. |
| 7 | Are the designated zone rules in the pack right, for goods within zones and goods sold to the mainland? | Goods within zones are outside scope when unmapped. Goods from a zone to the mainland are flagged. A mapped tax code always stands. |
| 8 | For partial unpaid supplier invoices, is the 6-month reversal pro rata to the unpaid part? | Reversal is pro rata. |
| 9 | Do the 1 Jan 2026 amendments change any calculation in the MVP? | Treated as no change in calculation: self-invoicing affects documents, the refund time limit affects COR-04 (Phase 2), and input denial for known evasion is a manual judgement. |
| 10 | Please verify the five golden returns in `fixtures/golden`. | I computed the expected values, so they are not independent evidence. |
