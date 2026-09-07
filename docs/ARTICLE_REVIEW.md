# Article review record

Writing completed September 8, 2026, after Arin's requested model switch.
The article is ready for author review. It is not published or author-approved.

Article: `docs/article.md` in the SDK checkout.
SHA-256: `c8663c33f093336b238f23919b529c332afc66a6ac7197fba6e8da752f752dd6`.
Production/build checkpoint: `8f41160842e144653bc5b5d4838eeb9d5e6c785c`.
The prior article draft and original writing checklist remain in Git at `22128b9`.

## Editorial and technical changes

The opening now leads with the reproduced producer-failure bug and the direct
API correction. The text explains compression-wrapper ownership, multipart
completion, terminal states, failed cleanup and ambiguous remote completion.
It gives both the fixed-part capacity and the separate process-memory budget.

The comparison names credible alternatives and preserves the settings and dates
attached to its numbers. It includes AWS's working buffered-retry option, the
historical signing correction, the fault-reset race and unknown timeout outcomes.
The later serial medians (0.61 s versus 0.60 s with overlapping IQRs) prevent the
earlier timing difference from being presented as a general speedup.

The article states local validation and release maturity accurately, explains
consumer-owned dependency policy and acknowledges AI assistance. It contains no
production/customer metrics, inferred internal lineage, AWS endorsement or paper
claim. The opening's misleading current-source link was removed.

## Verification

| Check | Result |
|---|---|
| Article performance table | Six adapters; all displayed time quartiles and heap/RSS medians match the archived main summary |
| Prose measurements | ZIP, AWS retry-buffer and patched-transport values checked against their separate summaries |
| Fault outcomes | Checked against final selected raw-derived CSVs; producer visibility, replay, orphans and six timeouts agree |
| Local links | All 17 article file/anchor links resolve |
| Java examples | All three fenced snippets compile under Java 11, 17 and 21 with Java 11 release target |
| Example scope | Application producer names are compile-only stubs; examples were not run against storage; the historical example remains semantically unsafe under the original contract |
| API/limits | Checked against current source, tests and the lifecycle decision record |
| External references | AWS multipart limits/lifecycle and blocking-output documentation, CI-CMG README and POI SXSSFWorkbook documentation rechecked September 8 |
| Author voice | Third-person artifact explanation; no invented personal experience; Arin's approval remains pending |
| Preserved inputs | Production/test/POM bytes, raw experiments, figure data, original BLOG.md and the frozen bundle remain unchanged |

The adjacent current claim ledger has 18 entries. S3-ENG-017 covers the later
serial timing comparison; S3-ENG-018 covers the historical AWS buffered-retry
performance. S3-ENG-016 now records completed writing and pending author review.

The technical bundle was frozen before this writing pass. Its original SHA-256
remains `58d2eeb64792ec7287a4f59034e22df2302c7dea9d8b1a93ffc8ba408b62d0b1`.
Its writing-status metadata and 16-entry claim ledger are historical. Use the
adjacent article, this review record and the current 18-entry claim ledger for
present writing status; raw measurements in the bundle remain authoritative.

## Owner review and publication

Arin should review the API contract, measured limitations, provenance note and
final prose before approving publication. The site correction audit identifies
duplicate claims that still need a separately authorized site edit. Distribution
also requires verified coordinates/namespace, publisher/signing setup and explicit
authorization for the exact code, artifacts and article. No public write, real-S3
test or external message was performed during this writing pass.
