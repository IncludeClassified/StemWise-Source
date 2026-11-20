# Supported source build and explicit artifact promotion

The source is self-contained under `source/`. Keep `source/bridge-core` and `source/test-suite` as siblings; do not copy only the Go module. The SDK build calls the included cleanup gate and Go suite runner, and compiles the Android client/network wrappers. It requires no installed bridge, private state, account login, tablet or running backend. Physical-device tests are not part of this source bundle.

Model/speed selection requires a native Codex runtime that advertises `model/list.serviceTiers` and accepts `thread/start.serviceTier` plus `turn/start.serviceTierForTurn`. These fields were checked in the installed CLI's generated schema (`0.163.0-alpha.11.2`); the bridge still validates each actual model catalog and preserves explicit native rejection. Missing tier metadata cannot authorize a tier. This source build uses executable fixtures and does not establish the installed account's tier entitlement or actual speed. Upgrading the app AAR alone does not update Pi/PC hosts; see `DEPLOYMENT.md`.

Supported host: Windows x64 with PowerShell 7 and Python 3.10+. The output is an **Android arm64-v8a/API 33+ SDK**, not a Windows ARM executable. Install the tool versions declared in `source/bridge-core/sdk-build-pins.json`: Go 1.27.1, x/mobile `v0.0.0-20260908204917-8b95e45f8d3e`, Android compile SDK 36, NDK 27.2.12479018 and JDK/javac 17.0.20. Both gomobile and gobind must come from that exact x/mobile version. Go dependencies are pinned in go.mod/go.sum. The build uses `GOTOOLCHAIN=local`, `GOWORK=off`, `-mod=readonly` and rejects different declared tool versions. A clean dependency/tool cache requires normal access to the pinned dependencies; existing verified caches can be reused.

Install the pinned mobile tools using the pinned Go toolchain:

```powershell
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260908204917-8b95e45f8d3e
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260908204917-8b95e45f8d3e
```

From the extracted package directory, substitute your installed tool paths. This one command builds, checks and publishes the AAR/manifest pair to the directory actually consumed by your app:

```powershell
& ./source/bridge-core/build-sdk.ps1 `
  -Go 'C:/toolchains/go1.27.1/bin/go.exe' `
  -Gomobile 'C:/toolchains/bin/gomobile.exe' `
  -Sdk 'C:/Android/Sdk' `
  -Ndk 'C:/Android/Sdk/ndk/27.2.12479018' `
  -Jdk 'C:/toolchains/jdk-17.0.20' `
  -PublishTo (Resolve-Path ./android).Path
```

Put the matching gobind beside gomobile. The build runs the included Go packages, compiles the Android wrappers, validates actual native LOAD/RELRO 16 KiB alignment, checks obsolete SDK exports and collects dependency notices. It removes its disposable staging. Failure never authorizes skipping the required checks.

Dependency notices use the same Android/arm64/cgo target and Android build tag as the AAR, including gomobile's generated `bind/seq` runtime. The builder restores its host Go settings after enumeration. The AAR notice asset and exported `THIRD_PARTY_NOTICES.txt` are identical; the focused notice check compares their complete module inventory with the actual native library's module receipt and checks an independent Windows graph as a negative control.

The native bind uses `-trimpath`. Because gomobile still embeds its absolute local module replacement, a first native bind supplies the actual module receipt. The standard-library `internal/sdkmodinfo` helper preserves its main package, dependencies and settings while making only local source paths relative. A second cached bind applies that receipt through the Go linker's `-X runtime.modinfo` and clears the directory-dependent Go build ID with `-buildid=`. Both standard Go build-info readers retain the normalized receipt; the builder checks that the final library preserves it. Settings describe the first-pass compilation and omit `-ldflags` under Go's `-trimpath` policy. No binary patching or dependency/tool-cache changes occur.

`sdk_archive.py` assembles both the nested `classes.jar` and outer AAR with sorted file entries, a fixed 1980-01-01 ZIP timestamp, fixed permissions and no variable comments or extra fields. It rejects missing, non-directory or linked source roots, linked descendants and JAR/class-directory entry collisions, including identical duplicate bytes. Before replacing an AAR it requires the manifest, a valid JAR containing classes, native library, ProGuard rules and notices; an empty `R.txt` remains valid. Archives are written and synced to an exclusive sibling temporary file, then replaced atomically, preserving the previous output on validation or replacement failure. Filesystem modification times and the checkout/build directory do not enter the archives. With identical source/dependency bytes and the same declared toolchain plus Python/zlib runtime, the output AAR is byte-identical across separate build directories. This does not claim identical bytes across different compilers or compression runtimes.

The Java compiler receives the top-level `bridge-core/android/*.java` paths from the captured production manifest's input inventory. Additional wrappers enter compilation and fingerprinting together; there is no separate fixed compiler filename list. A source change during compilation is still rejected before artifact-pair publication.

`python source/test-suite/integration/check_sdk_reproducible.py --go <go.exe> --gomobile <gomobile.exe> --sdk <sdk-directory> --ndk <ndk-directory> --jdk <jdk-directory>` performs two real SDK builds in separate disposable source/build directories, with the complete mobile package tests and all normal native/Java/archive/identity gates. It compares the complete AAR bytes; it does not publish over the delivered artifact pair. Timeout cleanup first retires the owned build process tree. If retirement cannot be confirmed, it reports and preserves the staging directory instead of deleting files still in use. Reuse the normal dependency caches through `GOCACHE`, `GOMODCACHE` and `GOPATH` when checking offline.

On a POSIX host, `python -m unittest discover -s ./source/test-suite/integration -p test_sdk_reproducible.py` exercises real harmless process trees and staging preservation without compiling an SDK. Windows skips these POSIX regressions; that skip is not evidence of timeout retirement.

For an SDK-only change, `-TestPackages './mobile','./files','./protocol'` runs the selected complete package tests and vet instead of all host/backend suites. The default remains all seven packages. Record the selection in the release handoff; this option does not skip Android compilation, artifact identity or packaging checks.

Successful canonical outputs are **`source/bridge-core/phone-pc-bridge.aar`** and **`source/bridge-core/phone-pc-bridge.build.json`**. `-PublishTo` explicitly publishes the matching pair to an existing artifact directory; omit it to leave app dependencies unchanged. Publication preserves unrelated files, rolls back a caught failure, and identifies the retained backup directory if rollback itself fails. The manifest is the commit point. A process interruption between replacing its two files can leave a mixed pair; verification rejects that state until a successful republish. Signing material and device state are outside this command.

The `bridge-sdk-artifact-v4` manifest binds the mobile target's actual transitive Android/arm64/cgo source closure, Android Java/manifest inputs, module files and build definitions to the exact AAR SHA-256/size and tool pins. Pinned Go discovers selected Go/Cgo/native files and embedded assets with `go list -deps -json -tags=android ./mobile`; module/toolchain pins bind external dependencies. Inputs are captured before compilation and checked again before publication. The builder derives `gomobile`'s minimum Android API and the wrapper compiler's `android.jar` path from the validated `androidApi` and `compileSdk` pins; only the declared `arm64-v8a` ABI is supported. Full host sources, tests and prose still ship with ZIP checksums. Backend/PC-only edits and Android-excluded sources do not invalidate the Android binary; selected mobile/shared/build changes do. Schema v4 requires a newly built matching artifact pair. Before compiling an app, check its exact consumed dependency directory:

```powershell
python ./source/bridge-core/sdk_artifact.py verify --go 'C:/toolchains/go1.27.1/bin/go.exe' --artifact-dir ./android
```

Unchanged verification needs Python only; it never invokes Go or downloads dependencies. The v4 manifest includes `candidateScopes` (local package directories and whether they embed assets), `candidates` (sorted candidate file hashes/sizes), and `inputsSha256` (the canonical selected-input inventory checksum). Candidate scans include non-test Go/native sources, including currently excluded build-tag files, ancestor module files and conservative recursive assets for embedding packages. This detects added `init` files, changed build tags and newly matched embedded files. Candidate assets are included in the source export. Selected bytes, AAR bytes, pins and inventory shape are always checked.

When candidates change, verification uses the exact pinned Go toolchain to rediscover the compiled closure. Newly selected sources/assets require an SDK rebuild. Changes that remain excluded can pass without rebuilding or modifying the AAR/manifest pair. Such a tree will rediscover on later verification while it differs from the published candidate baseline. Use explicit `--go` in Gradle/CI when pinned Go is not on PATH; it is only needed for changed candidates, source capture/export and SDK builds. If omitted, only `go` on PATH is used when discovery is necessary; a wrong or missing version fails without fallback, toolchain switching or download. Discovery requires the pinned module dependencies in normal caches/network and is bounded; unavailable/incomplete results or mutable local replacements outside the shipped module fail explicitly. No Android SDK, NDK or JDK is needed for verification. There is no `BRIDGE_GO` environment interface.

Consumer build systems must track candidate additions, not just existing `inputs` rows. Reuse `sdk_artifact.py`'s `verify(root, artifact_dir, go=..., force_discovery=False)` and `candidate_snapshot(root, metadata["candidateScopes"])` Python callables, with `pathlib.Path` roots. The latter returns sorted current `{path, sha256, size}` rows without Go. Set `force_discovery=True` (CLI: `verify --discover`) for an explicit exact-Go check even when candidates match. Include the current candidate paths or their scoped directory trees, the manifest, selected inputs and consumed AAR in Gradle/CI task inputs; this keeps added files from bypassing an up-to-date check. Do not mirror a selected-file-only verifier or treat candidate rows as compiled files. Malformed scopes/inventories fail. Test-only/prose/host-only changes outside embedding scopes do not trigger dependency discovery.

A changed/missing selected input, newly selected source or asset, mismatched pins or mixed AAR/manifest pair fails. Ordinary comments remain part of their selected production file's byte identity. Host source verification/build/testing remains a separate responsibility; Android artifact validity does not establish host deployment. The top-level `SHA256SUMS.txt` covers all originally delivered files, including host sources, tests and prose; after edits it is no longer a receipt for the modified tree. Exact SDK artifact identity is the AAR SHA-256; byte reproducibility requires the same inputs and toolchain described above. These checks detect mismatched bytes; they are not an independent signature or an adversarial attestation system.

Keep all permanent tests in `source/test-suite`. Its runner copies real production and suite files into one disposable complete Go module, then uses ordinary Go discovery, explicit vet and uncached tests with a two-minute runtime limit. No overlay or production-source replacement is used. CI must run the supported runner rather than bare `go test` in the production-only module, and give the whole job its own timeout:

```powershell
& ./source/test-suite/Run-Go.ps1 -Go 'C:/toolchains/go1.27.1/bin/go.exe'
& ./source/test-suite/GoDiscoveryCheck.ps1 -Go 'C:/toolchains/go1.27.1/bin/go.exe'
$env:PATH = 'C:/toolchains/go1.27.1/bin;' + $env:PATH
$env:JAVA_HOME = 'C:/toolchains/jdk-17.0.20'
python -m unittest discover -s ./source/test-suite/integration -p test_sdk_artifact.py
python -m unittest discover -s ./source/test-suite/integration -p test_sdk_build.py
```

The real-compiler regression uses the configured `JAVA_HOME` and verifies compilation through an owned temporary JDK path containing spaces; it never assumes a vendor installation directory. Missing configuration or a compiler differing from the pinned version fails explicitly. The other builder fixtures retain their fake external-tool boundaries. After building/packaging the artifact pair, `python -m unittest discover -s ./source/test-suite/integration -p test_sdk_notices.py` checks the real Android dependency graph, linked native module inventory and exported notices. In the maintainer checkout use `./test-suite/integration` instead of `./source/test-suite/integration`.

The discovery check proves that an empty suite, an actual failing regression and a real vet error fail the runner, then verifies the corrected behavior passes. Add `-Json` to `Run-Go.ps1` for Go's actual JSON test events; it preserves discovery, vet, uncached test selection, timeouts and cleanup. A required-test gate must check the runner's exit status and an exact package/test `Action=pass`; an absent or skipped test does not qualify. The discovery fixture exercises the actual pass/skip distinction. Windows process ownership and retirement are covered without inference by `TestOwnedCLIStopsActualDescendants`, `TestRetireTurnRequiresExactTerminalAndObservedEmptyBackgroundWork` and `TestClosedStdoutOrStartupCancellationReapsOwnedChildWithinBound`, using actual fixture subprocesses and process-exit observation.

The optional native Windows cancellation test additionally requires `BRIDGE_NATIVE_CODEX` naming the existing CLI, `BRIDGE_NATIVE_PROJECT` naming the Bridge project, and explicit `BRIDGE_NATIVE_INFERENCE=1`. It creates one owned fixture chat, asks its model to launch a harmless sleep command, verifies actual child-process exit after cancellation/retirement, and deletes the fixture chat. It uses the existing account and does not contact app developers. Ordinary source builds and non-inference CI skip that account-dependent test.

On a supported host, `source/test-suite/Run-Go.ps1 -Go <pinned-go> -Race` enables the actual Go race detector while retaining discovery, vet, isolated staging and cleanup. It requires the host's supported cgo compiler/runtime; unsupported race builds fail rather than silently dropping the flag. Windows' normal build remains unchanged. Optional -Run selects a top-level Go test regex; default . keeps the complete normal gate. See RACE_TESTS.md for the fixed backend concurrency race case set and base-bound tooling patch.

The maintainer-only `source/test-suite/CommentaryLiveCheck.py --run` starts one real dummy Codex job and therefore requires separate explicit owner approval. It refuses without that flag, aborts if the caller already has a registration, preserves the original request ID before submission, checks recognized reading/testing markers through the API before the turn ends, verifies the inline result bytes, then retires its owned job/chat/registration. `functionalPassed` covers those outcomes; `plainTokenConformance` separately records whether every observed marker used the required exact plain formatting. Supported normalized markers can establish functional progress without establishing plain-token compliance; unrecognized markers cannot. It never wakes devices or uses app-owned data. Do not run it from ordinary app builds or CI without that authorization; controlled regression fixtures are not evidence of actual model compliance.

## Failed native startup ownership

A source-level `codex.Connect` failure may return a `StartupCleanupError` containing `Owner` when cleanup cannot prove retirement. Preserve that owner and both wrapped startup/cleanup causes; the failed connection is not usable for new RPCs. Do not start a replacement until its `Close` confirms retirement. The maintained PC service retains this owner automatically, reports the failure and refuses another worker while cleanup is uncertain. This includes partial process startup, failed initialization and cancellation. A startup failure with confirmed cleanup has no retained owner. Existing service-close handling also preserves unresolved owners and propagates cleanup errors.

## Cancellation API change (APP-03)

Local file calls now take a **caller-owned operation ID first**: `prepare`, `prepareFile`, `restorePreparedFile`, `pending`, `received`, `readReceived`, `receivedInfo`, `copyReceived`. Remote methods retain their existing operation-ID argument. Use `android/API.txt` generated from this exact AAR for complete signatures. Update source and binary together; do not keep an old AAR behind new source bindings.

Each synchronous SDK call registers cancellation before local file copying/hashing and retains one context across all its internal HTTP exchanges and local IO. `Client.cancel(operationId)` latches cancellation for that active call until it returns. Child HTTP completion cannot retire the logical operation. A cancelled descriptor request cannot proceed to content download; a cancelled import cannot retain a partial new destination; cancelled delivery hashing cannot initiate acknowledgment. The operation unregisters when its actual synchronous work retires. Fresh operation IDs identify new calls, not durable jobs or transfer IDs.

The app must separately latch cancellation of its own multi-call workflow. Scheduled cancellable calls MUST reserve `client.newOperation()` before publishing/scheduling the worker; pass `operation.token()` as the operationId. A plain caller-generated ID only cancels an already active SDK call and does not close the cancel-before-begin race. The reserved token is single-use: cancelled, expired, completed or foreign-client tokens fail with `bridge_operation_closed` instead of starting a fresh call. `operation.cancel()` and `client.cancel(operation.token())` both latch before SDK entry. Close a lease in the worker's actual finally block and also on scheduling rejection or abandonment. Closing/cancelling a running lease cancels work but retains its SDK slot until actual SDK finish. At most 128 leases are outstanding per client; unused leases expire after 30 minutes. No permanent cancellation tombstones or repeated-cancel loop are required.

Cancellation is cooperative between bounded regular-file reads. It cannot abort an OS syscall that has not returned, undo a remote mutation already accepted, cancel the Pi job merely by ending observation, or prove an interrupted upload/delivery had no effect. Reconcile original job/transfer/delivery receipts, preserve immutable IDs and never blindly replay uncertain work. Explicit local cache cleanup actions remain separate from delivery claims.

The SDK build runs the Go/vet, Android compilation, cleanup, native alignment, license and export gates before publication. Packaging verifies the exact captured AAR, manifest and production source bytes it ships, and derives workflow/error contracts from those same captured sources. Shipped test files are ordinary source snapshots covered by bundle checksums. No empty dependency cache or physical Android-device run is implied by these gates.

## Safe host handoff before SDK registration

Create the lease before publishing a phase or starting its FutureTask. Lease creation performs no network IO. Create it outside the app's root lock, then atomically check the parent's latched cancellation and publish the exact lease/FutureTask pair under that lock. Cancellation sets the parent flag and captures that same pair under the same lock, then cancels both outside the lock. A cancel that wins before publication causes the new lease to be closed without execution. A cancel after publication reaches the reserved SDK lease even when the worker started but has not entered SDK begin.

For example, the following is one send phase (the parent owner supplies flowLock/currentPhase/currentFuture/parentCancelled):

```java
Operation phase = client.newOperation();
FutureTask<String> task = new FutureTask<>(() -> {
    try { return files.send(phase.token(), originalTransferId); }
    finally { phase.close(); }
});
boolean accepted;
synchronized (flowLock) {
    accepted = !parentCancelled;
    if (accepted) { currentPhase = phase; currentFuture = task; }
}
if (!accepted) {
    phase.close(); task.cancel(false);
    throw new CancellationException();
}
try { executor.execute(task); }
catch (RejectedExecutionException rejected) {
    phase.close(); task.cancel(false);
    throw rejected;
}
```

The matching cancellation path captures immutable ownership; it must not look up a newer global client/phase later:

```java
Operation phase;
FutureTask<?> task;
synchronized (flowLock) {
    parentCancelled = true;
    phase = currentPhase;
    task = currentFuture;
}
if (phase != null) phase.cancel();
if (task != null) task.cancel(true);
```

Keep the app's existing actual-worker retirement accounting. FutureTask.done/get reporting cancellation is not proof that a running JNI call retired. Retire a running worker only from its actual run/finally exit; a conclusively never-started/rejected task can retire without invoking the SDK. Cancelling a lease that never entered SDK frees its reservation immediately, and a late worker using that closed token is refused. The SDK retains ownership of a running lease until SDK finish. Do not publish a later app phase while the parent is cancelled or the previous worker still owns the root.

Recommended composition: openAppFiles is one reserved phase. After it returns and the owned worker has actually retired, check/publish under the parent's cancellation lock. Then prepare/send/receipt/receiveMatching/copyReceived/confirmDelivery each use their own fresh lease and exactly one blocking SDK call. In particular, split receive from copy and send from receipt. Explicit local cache cleanup remains a separate housekeeping action. Preserve original durable job/transfer IDs and timestamps; SDK tokens never replace them. The token is local control metadata, not a server job ID or a transfer ID. An already-started remote mutation remains uncertain after cancellation and still requires receipt reconciliation.

For an upload that may have stopped before send or lost its acknowledgment, prefer one `files.ensureSent(phase.token(), originalTransferId)` call. Its probe and conditional same-ID send are one cancellable SDK phase. Only a strictly correlated absent receipt permits sending; an existing reservation/receipt is observed, and corruption remains an explicit failure. `storageDiagnostics(phase.token())` is a separate local inspection phase. Neither method submits a native job or changes durable IDs.

Reserved-phase local errors are bridge_operation_closed (cancelled, expired, consumed or foreign token), bridge_operation_capacity_unavailable (128 outstanding leases; release/retire owned phases) and bridge_operation_id_conflict (allocation refused without overwriting another owner). These are SDK errors, separate from the HTTP app error catalog. Tokens are process-local control handles: do not persist them in durable job data. After process restart, reserve fresh SDK phases while preserving the original server job/transfer IDs. The parent owner must also enforce that no previous actual worker owns the root before publishing a later phase.
