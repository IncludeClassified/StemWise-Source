# Focused backend race gate

Keep the normal full Go/vet gate. Run its default selection without `-Run`; its two-minute package deadline and uncached execution are unchanged.

For the additional supported Linux/cgo race lane, use the canonical concurrency set:

```powershell
$required = @(Get-Content -LiteralPath ./test-suite/backend-race-cases.json -Raw | ConvertFrom-Json)
$filter = '^(' + (($required | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')$'
./test-suite/Run-Go.ps1 -Go <pinned-go> -Packages ./backend -Race -Json -Run $filter
```

`-Run` is a top-level Go test regex. Discovery refuses invalid or unmatched filters in each selected package; slash/subtest filters are intentionally refused because top-level discovery cannot establish that a requested subtest exists. JSON output still contains actual Go events. The CI caller must require a top-level `pass` event for every name in the case list, reject missing/skipped required cases and propagate the runner's nonzero result. A timeout or absence of race warnings is not a passing race verdict. Go's race detector requires a supported host/cgo toolchain; it is never silently disabled.

These cases exercise concurrent dispatch, shutdown/draining, fair worker progress, leases, body receipt, cancelled observations/admission, blocked streams, blocked response writers, cancellation racing authentication refusal, exact-owner publication and native cancellation retirement. The record-volume/save-failure fixtures remain in the complete normal gate; they are not weakened or removed to fit race overhead.

For a maintainer-provided tooling-only update to an already verified consumed bundle, verify every original checksum first. Copy only the canonical `Run-Go.ps1`, `GoDiscoveryCheck.ps1` and `backend-race-cases.json` into its `source/test-suite/`, then install the complete replacement `SHA256SUMS.txt` prepared by the Bridge maintainer for that exact base. Verify the complete new manifest afterward. Production Go/test inputs and the AAR/manifest/API must stay byte-identical; do not copy a newer production source tree or commentary feature merely to use this runner. No permanent parallel runner, patch archive or historical source bundle is required.
