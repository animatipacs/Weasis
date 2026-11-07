# Implementation Plan — Decode images concurrently instead of on one process-wide thread (3.8.x)

**Spec:** [`docs/specs/concurrent-image-decoding.md`](../specs/concurrent-image-decoding.md) (the fork change request, FCR, is quoted verbatim at its end; "FCR §n" below points there)
**Branch:** `change/concurrent-image-decoding` (cut from `389f939`, tag `v3.8.1`; `3.8.x` untouched)
**Date:** 2026-11-07
**Status:** approved

## Context

Every pixel decode Weasis performs through `ImageElement.getImage()` and through thumbnails runs on one
process-wide single-thread executor, `ImageElement.IMAGE_LOADER`
(`weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/ImageElement.java:41-42`). Callers that
load a series from several threads gain nothing. Two latent races in `DicomMediaIO` stay hidden only because
decodes never overlap: the lazy fragment index (`DicomMediaIO.java:287`, `:1118-1133`) and a reset that clears
`iis`, `dis` and `tsuid` under a running decode (`:305`, `:1188-1206`). The change makes `IMAGE_LOADER` a fixed
pool of daemon threads sized from cores and heap, with a system property that sets it explicitly (`1` restores
today's single thread); it never drops a decode and never runs one on the caller's thread; an interrupted caller
leaves no queued task and no stale loading mark; and it makes `DicomMediaIO` safe for concurrent decodes of one
reader. Every public and protected signature of `ImageElement`, `MediaElement`, `Thumbnail` and `DicomMediaIO`, the
field `public static final ExecutorService IMAGE_LOADER`, the per-element single decode, the cache semantics, the
out-of-memory handling and the decoded pixel values stay as they are (FCR §2 item 3).

The plan follows FCR §5 tasks 3 to 6 in order, never reordered or merged across a gate (`CLAUDE.md` rule 3):

| Increment | FCR §5 task | Executed by |
|---|---|---|
| 1 | 3 — acceptance tests first, run on the unchanged code under the stop rule | `abs.build` |
| 2 | 4 — implement FCR §2 | `abs.build` |
| 3 | 5 — set the internal version | the release half (`CLAUDE.md` rule 6: a change branch edits no POM version) |
| 4–7 | 6 — criterion 6 on every platform the native library ships for, one increment per machine | the operator on each machine; the release approver for exceptions |

`abs.build` executes Increments 1 and 2 and stops; it leaves Increments 3–7 unchecked. FCR §5 task 7 (leak scan and
release) is the release half's and is not an increment here.

**Decisions taken at planning (2026-11-07, developer)** — they close spec §8 Open Questions 2 and 3:

- **Unbounded queue, as today** (spec §8 Q2). Submission never blocks, so **AC-9 does not apply** and the
  "not yet submitted" interrupt path of the FCR §2 *Concurrency contract* does not exist. A cancelled task is still
  removed from the queue through the private `ThreadPoolExecutor` reference (FCR §2 *Queue bound*: "In both").
- **Pool size: upstream `v4.6.3`'s formula** `max(2, min(min(16, maxHeapMB / 100), 2 × availableProcessors))`,
  overridden by the system property **`weasis.image.loader.threads`** (spec §8 Q3). A value below 1 or not an
  integer falls back to the formula and logs a warning.
- **Task 5 is a hand-off** to the release half (Increment 3); **task 6 is one increment per machine**: Windows
  x86-64 (this machine), Linux x86-64 (the developer's dual-boot notebook), macOS x86-64 through Rosetta on an
  Apple Silicon Mac. The four platforms with no machine (Windows x86, Linux x86, Linux armv7a, Linux aarch64) get
  an exception recorded by the release approver (Increment 7). This closes how spec §8 Q1 will be closed; Q1 itself
  closes when Increments 4–7 are recorded.

**Mockups:** spec §4 is `N/A — no UI in this change` (Case A). No visual reference.

**Context-layer gaps:** no `coding-guidelines.md` or `testing-guidelines.md` exist. The adjacent tests
(`weasis-dicom/weasis-dicom-codec/src/test/java/org/weasis/dicom/codec/TagDTest.java`,
`weasis-core/weasis-core-api/src/test/java/org/weasis/core/api/media/data/TagWTest.java`) are the conventions:
JUnit 4, AssertJ, test class in the production class's package.

## Prerequisites

- Maven 3.9.9 and JDK 17 as in `architecture.md` *How to build and test*; the baseline `mvn -B -pl
  weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am test` is green at `389f939` (19 + 9 tests).
- The native codec `org.weasis.thirdparty.org.opencv:opencv_java:4.5.3-dcm` resolves from the repositories the
  parent POM already declares; on this machine the `windows-x86-64` `dll` is already in the local repository.
- Increments 5 and 6 need, on their machine, a clone of the branch, JDK 17 and Maven 3.9.x; the Mac needs an
  **x86-64** JDK 17 run through Rosetta 2 (Increment 6).

## Architecture

No new module or directory. Two internal structures change; the public surface does not.

- **`ImageElement`** keeps `public static final ExecutorService IMAGE_LOADER`, now assigned from a new
  `private static final ThreadPoolExecutor` (core = max = pool size, `LinkedBlockingQueue` unbounded, daemon threads
  named `Image Loader-1` … `Image Loader-<n>`). A package-private static helper cancels a submitted `Future` and
  removes it from that private pool; both submitters (`ImageElement.startImageLoading()` and
  `Thumbnail`, same package) call it on interrupt.
- **`DicomMediaIO`** publishes its fragment index once per reader (built in a local list under the reader's
  monitor, then assigned to a `volatile` field), and counts in-flight decodes around the protected
  `getImageFragment(MediaElement, int)`. A `reset()` that arrives while the count is above zero is deferred and runs
  exactly once when the count returns to zero. A `close()` (→ `dispose()`) that arrives meanwhile is deferred whole:
  the last decode to end removes the reader's header from `HEADER_CACHE` and then resets the reader, as a `close()`
  with no decode in flight does. A decode takes the transfer syntax from the header it reads (cached or read from
  the file), which keeps the syntax found by that header read, not from the reader's parse state, which a reset
  clears while the header can stay cached (a reset deferred by a decode runs after that decode may have read and
  cached the header again). *Build-time deviation:* the count and the pending reset and dispose are guarded by a
  private lock of the reader (`decodeLock`), not by its monitor. `readMetaData()` holds the monitor during header I/O, and its `HEADER_CACHE.get` / `put` can run another
  reader's `reset()` through the soft cache's removal callback, so a `reset()` on the monitor could deadlock two
  readers. The private lock is never held while decoding or while calling `HEADER_CACHE`, so no two readers' locks
  nest. `HEADER_CACHE` (a `SoftHashMap` backed by plain hash maps, used at once by decodes on the pool and by the
  image cache's evictions) holds its own monitor in every method that reads or changes its maps; the overrides are in
  the anonymous subclass in `DicomMediaIO`, and `SoftHashMap` itself is unchanged. *Lock order:* the
  `ImageElement` monitor → the image cache's map (`NativeCache.hash`, whose eviction calls `reader.close()`) →
  `HEADER_CACHE`'s monitor → a reader's `decodeLock`; and a reader's monitor → `HEADER_CACHE`'s monitor → a
  reader's `decodeLock`. Under `HEADER_CACHE`'s monitor only the maps, the reference queue and, in the removal
  callback, the evicted reader's `reset()` run, which takes that reader's `decodeLock` and nothing else; under a
  `decodeLock` only the reader's stream is closed. No path takes these locks in the reverse order.

```
caller thread ──getImage()──▶ startImageLoading() ──submit(Load)──▶ IMAGE_LOADER (= private ThreadPoolExecutor, n daemon threads)
      ▲                         │  on interrupt: cancel + remove(future), setAsLoaded()           │
      └──── future.get() ◀──────┘                                                                ▼
                                                     Load.call() → loadImage() → MediaReader.getImageFragment()
                                                                                  │ DicomMediaIO: inFlight++ … inFlight-- (deferred reset)
                                                                                  ▼
                                                     Imgcodecs.dicomRawFileRead / dicomRawMatRead / dicomJpgFileRead (native)
```

## AC Traceability

| AC | Description | Increment |
|----|-------------|-----------|
| AC-1 | Pool ≥ 2: two elements' `getImageFragment()` overlap (`[new behaviour]`) | 1 (red), 2 (green) |
| AC-2 | Property `1`: the two calls never overlap (`[guard]`) | 1 (green), 2 (green) |
| AC-3 | Eight callers of one element: one decode, one shared instance (`[guard]`) | 1 (green), 2 (green) |
| AC-4 | Multi-frame, multi-fragment JPEG 2000 and JPEG decoded from 8 threads through one reader, ≥ 50 repetitions (`[new behaviour]`) | 1 (red), 2 (green) |
| AC-5 | `close()` during a decode: same pixels, reset runs after (`[new behaviour]`) | 1 (red), 2 (green) |
| AC-6 | Each listed syntax decoded from 8 threads × 20 rounds, pixel-identical (`[guard]`) | 1 (green, serialized), 2 (green, concurrent, Windows x86-64); 4, 5, 6 (per platform) |
| AC-7 | Interrupted caller: interrupt status set, later call decodes (`[guard]`) | 1 (green), 2 (green) |
| AC-8 | Pool size 1, many callers: every decode on a pool thread (`[guard]`) | 1 (green), 2 (green) |
| AC-9 | Bounded queue only | **Not applicable** — unbounded queue chosen (Context, *Decisions*) |
| AC-10 | Internal version `3.8.2` in the parent's `revision` | 3 (release half) |
| AC-11 | Every platform check recorded or excepted | 4, 5, 6, 7 |
| AC-12 | Leak scan clean | FCR §5 task 7 — the release half; outside this plan |

## Reuse Inventory

**Utilities / helpers / pure functions:**
- `weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/util/ThreadUtil.java:61-67` — `getThreadFactory(name)`; its naming (`<name>-pool-k-thread-m`) and non-daemon threads do not meet FCR §2 item 1, so the new factory is net-new beside it, same naming prefix.
- `weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/util/ThreadUtil.java:32-33`, `:52-53` — existing executor builders; replaced at the `IMAGE_LOADER` site only, left in place for other callers.
- `org.opencv.imgcodecs.Imgcodecs.dicomJpgWrite(Mat, MatOfInt, String)` (`weasis-core-img` 4.5.3) — the native encoder the codec tests use to generate JPEG baseline / lossless, JPEG-LS and JPEG 2000 fixtures in the test.

**Services / domain logic / repositories:**
- `weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/ImageElement.java:409-443` — `startImageLoading()`: the submit / wait / interrupt / `setAsLoaded()` path that gains the queue removal.
- `weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/ImageElement.java:82-105` — `findMinMaxValues()`: its 8-bit branch (`:88-91`) lets the fake image skip the native `minMaxLoc`.
- `weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/MediaElement.java:141-151` — `setAsLoading()` / `setAsLoaded()`: the per-element single-decode guard, unchanged.
- `weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/Thumbnail.java:314-336` — the second submitter; its interrupt branch (`:332-336`) gains the queue removal.
- `weasis-dicom/weasis-dicom-codec/src/main/java/org/weasis/dicom/codec/DicomMediaIO.java:714-729` — public / protected `getImageFragment`: the in-flight counter wraps the protected call.
- `weasis-dicom/weasis-dicom-codec/src/main/java/org/weasis/dicom/codec/DicomMediaIO.java:1110-1150` — `buildSegmentedImageInputStream()` multi-fragment branch: the index build moves into a local list.
- `weasis-dicom/weasis-dicom-codec/src/main/java/org/weasis/dicom/codec/DicomMediaIO.java:1180-1206` — `dispose()` / `reset()` / `resetInternalState()`: the reset that is deferred.
- `weasis-dicom/weasis-dicom-codec/src/main/java/org/weasis/dicom/codec/DicomMediaIO.java:1217` — `readMetaData()` is already `synchronized` on the reader: the reader's monitor is the lock the index build uses, and the decode takes the transfer syntax from the header it returns. *Build-time deviation:* the counter uses a private lock of the reader instead (see Architecture), because a `reset()` on the monitor could deadlock two readers through `HEADER_CACHE`.

**Middleware / framework primitives:**
- `java.util.concurrent.ThreadPoolExecutor` with `LinkedBlockingQueue` and `remove(Runnable)` — the pool and the queue removal FCR §2 item 1 names; `Future` returned by `submit` is the queued `FutureTask`.
- `org.dcm4che3.io.DicomOutputStream` / `org.dcm4che3.data.Fragments` (dcm4che 5.25.1, via `weasis-dicom-tools`) — write the generated DICOM fixtures, including encapsulated pixel data split into several fragments per frame and the deflated transfer syntax.

**Platform / stack / framework defaults:**
- JUnit 4 + AssertJ (`weasis-core-api/pom.xml:70-71`; parent `:513-527`), Surefire `3.0.0-M5` (`weasis-parent/pom.xml:170-174`) — native test runner; `reuseForks=false` gives each test class its own JVM so the static pool size is read per class.
- `maven-dependency-plugin` `copy` (`weasis-parent/pom.xml:175-179`; pattern at `weasis-opencv/weasis-opencv-core-windows-x86-64/pom.xml`) — puts the platform's `opencv_java` native library where the codec tests load it.
- `java.util.concurrent` `CountDownLatch`, `CyclicBarrier`, `AtomicInteger` — test synchronization; no sleeps as synchronization.

## Increments

**Run notes (spec §10).** Item 1 (the plan) is filled by planning. Item 2 (the build and the evidence rule) is
filled by Increments 1 and 2. Item 4 (the platform checks) is filled by Increments 4–7. Item 3 (the leak scan) is
FCR §5 task 7, the release half's. Each increment's **Run note:** says which item it writes, in Weasis terms only.

### - [x] 1. Acceptance tests first (FCR §5 task 3) — write AC-1 to AC-8 and run them on the unchanged code under the stop rule

**Covers:** AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8 (AC-9 not applicable)
**Why a test-only increment:** FCR §5 makes task 3 a gate with its own recorded result before task 4 starts, and
`CLAUDE.md` rules 3 and 4 forbid merging across it.
**ATDD Scenarios:**

```gherkin
# weasis-core-api — ImageLoaderPoolTest (JVM with -Dweasis.image.loader.threads=4)
Scenario: AC-1 two elements decode at the same time
  Given weasis.image.loader.threads=4 and elements E1, E2 on BlockingFakeReader with the release latch closed
  When thread T1 calls E1.getImage() and thread T2 calls E2.getImage()
  Then within 5 s the reader's in-progress count reaches 2 before either call returns
  # unchanged code: the count stays 1 for 5 s → FAIL (expected RED)

Scenario: AC-3 eight callers of one element share one decode
  Given element E on BlockingFakeReader returning the single FakePlanarImage instance IMG, and a CyclicBarrier(8)
  When 8 threads pass the barrier then call E.getImage(), and the latch opens only after the barrier opened and the first getImageFragment() started
  Then getImageFragment() ran exactly 1 time and all 8 callers received IMG (same reference)

Scenario: AC-7 interrupted caller
  Given thread T1 blocked in E.getImage() with the latch closed (getImageFragment() in progress)
  When T1 is interrupted
  Then T1's call returns within 5 s, T1.isInterrupted() was true at return, and the latch is still closed
  And after the test opens the latch, E.getImage() from thread T2 returns a non-null image
  And the test does not fail if T2's getImageFragment() overlapped the abandoned one

# weasis-core-api — ImageLoaderSingleThreadTest (JVM with -Dweasis.image.loader.threads=1)
Scenario: AC-2 size 1 never overlaps
  Given weasis.image.loader.threads=1 and elements E1, E2 on BlockingFakeReader
  When T1 calls E1.getImage() and T2 calls E2.getImage(), and the latch opens after a 500 ms observation window
  Then the reader's maximum in-progress count is 1 and both calls return a non-null image

Scenario: AC-8 decodes never run on a caller's thread
  Given weasis.image.loader.threads=1 and 32 elements, each on its own BlockingFakeReader
  When 32 caller threads each call getImage() on their element and the latch opens
  Then every recorded Thread.currentThread() of getImageFragment() is not one of the 32 callers and its name starts with "Image Loader"

# weasis-dicom-codec — native codec loaded; fixtures generated in a TemporaryFolder
Scenario: AC-4 one reader, frames decoded from 8 threads
  Given a fresh 8-frame JPEG 2000 lossless file and a fresh 8-frame JPEG baseline file, 3 fragments per frame, empty basic offset table
  When for each of 50 repetitions a new DicomMediaIO R is opened and 8 threads, released together by a CyclicBarrier, each call R.getImageFragment(R.getMediaElement()[i]) directly (one frame per thread, bypassing IMAGE_LOADER so the unchanged single thread cannot serialize them)
  Then every frame equals the serial decode of that frame pixel for pixel
  And no IOException "Cannot match all the fragments to all the frames!" is raised
  # unchanged code: the lazy index is filled twice → the exception → FAIL (expected RED)

Scenario: AC-5 close() during a decode
  Given an 8-frame reader (a test subclass of DicomMediaIO whose protected getImageFragment(MediaElement, int) signals "started" then waits on a latch before calling super)
  When thread T1 calls the reader's getImageFragment() for frame 3 directly, and once "started" fires thread T2 calls close() and returns, then the latch opens
  Then T1's image equals an undisturbed decode of frame 3
  And after T1 returns, the reader's iis, dis and tsuid are null, as after a close() with no decode in flight
  # unchanged code: close() clears dis and tsuid under the held decode → the test's check, made after close() returns while the decode is still held, that dis and tsuid are still set fails first → FAIL (expected RED)

Scenario: AC-6 each syntax from 8 threads, own element and reader
  Given single-frame 64×64 fixtures in explicit VR little endian, deflated, RLE lossless, JPEG baseline, JPEG lossless, JPEG-LS lossless, JPEG-LS near-lossless, JPEG 2000 lossless, JPEG 2000 lossy
  When for 20 rounds 8 threads each decode every file through their own DicomImageElement and DicomMediaIO
  Then every result is pixel-identical to a separate serial decode of the same file
  # unchanged code: GREEN (serialized by the single thread); this is the guard's first run
```

**Approach:**
- *weasis-core-api test fakes* (`src/test/java/org/weasis/core/api/media/data/`): `BlockingFakeReader implements
  MediaReader` — `getImageFragment()` increments an `AtomicInteger` in-progress counter and tracks its maximum,
  counts calls, records `Thread.currentThread()`, counts down a "started" latch, waits on the shared release latch
  (bounded wait, e.g. 30 s, so a broken run fails instead of hanging), then returns the image. A distinct `URI` per
  reader keeps elements distinct in `NativeCache`. `FakePlanarImage implements PlanarImage` — type `CV_8UC1`, 2×2,
  small `physicalBytes()`; the 8-bit branch of `findMinMaxValues()` (`ImageElement.java:88-91`) never calls native
  code; `toMat()` throws `UnsupportedOperationException` so an unexpected native path fails loudly.
- *Pool size per JVM*: `IMAGE_LOADER` is a static field read once per class load. Two test classes, each setting
  `weasis.image.loader.threads` in a `static {}` block before `ImageElement` is touched; `weasis-core-api/pom.xml`
  configures Surefire `reuseForks=false` so each class gets a fresh JVM. Every test's `finally` opens the latch and
  joins its threads with a timeout.
- *Native codec in the codec tests*: `weasis-dicom-codec/pom.xml` gets three OS-activated profiles
  (`windows` + `amd64`; `unix`/`Linux` + `amd64`; `mac` + `x86_64`) that `copy` `opencv_java:
  ${weasis.opencv.native.version}` with type `dll` / `so` / `jnilib` and classifier `windows-x86-64` /
  `linux-x86-64` / `macosx-x86-64` into `target/native` at `generate-test-resources`, and pass that directory to
  Surefire as a system property. A test helper `System.load`s it once; if no profile matched, the AC-4/5/6 tests
  **fail** with the `os.name` / `os.arch` they saw (never skip: a skipped guard is not evidence).
- *Fixtures* are generated in the test, synthetic only (gradient pixels, generated UIDs, no person attributes):
  compressed frames by `Imgcodecs.dicomJpgWrite`; each multi-fragment frame's codestream split into 3 fragments
  so only the first starts with SOI/SOC (the markers `decodeJpeg` / `decodeJpeg2000` look for,
  `DicomMediaIO.java:1303`, `:1341`) and written with an empty basic offset table through dcm4che `Fragments`;
  deflated through `DicomOutputStream` with the deflated syntax; RLE through a small test-side PackBits encoder
  (DICOM PS3.5 Annex G) unless dcm4che 5.25.1 ships an RLE writer. This closes spec §8 Q6.
- *AC-4 and AC-5 call the reader directly* (`DicomMediaIO.getImageFragment(MediaElement)` from the test threads), not
  `getImage()`: the races live in the reader, and on the unchanged code `getImage()` would serialize them on the
  single thread and hide them. AC-6 goes through `DicomImageElement.getImage()`, as FCR §3 (6) says.
- *AC-5's observation* of `iis`, `dis`, `tsuid` reads the private fields by reflection in the test (same package);
  production code gains no accessor.
- *Gate record*: run `mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am test` on the
  unchanged main code. Expected: AC-1, AC-4, AC-5 fail in the way FCR §2 *Failing fixtures* describes; AC-2, AC-3,
  AC-6, AC-7, AC-8 pass; the 28 existing tests pass. **Stop** (FCR §5 task 3) if AC-1, AC-4 or AC-5 passes, or if
  AC-2, AC-3, AC-7 or AC-8 fails: the fixture is wrong, not the code. Record each test's result and failure message.
- Commit the tests only (no main-code change in this increment).

**Affected files:** `weasis-core/weasis-core-api/src/test/java/org/weasis/core/api/media/data/` (new
`BlockingFakeReader`, `FakePlanarImage`, `ImageLoaderPoolTest`, `ImageLoaderSingleThreadTest`),
`weasis-core/weasis-core-api/pom.xml` (Surefire `reuseForks=false`; no version),
`weasis-dicom/weasis-dicom-codec/src/test/java/org/weasis/dicom/codec/` (new fixture generator, native loader,
`ConcurrentFrameDecodeTest` (AC-4), `ReaderCloseDuringDecodeTest` (AC-5), `ConcurrentSyntaxDecodeTest` (AC-6)),
`weasis-dicom/weasis-dicom-codec/pom.xml` (native-copy profiles; no version)
**Depends on:** None (first increment); FCR §5 tasks 1–2 recorded in the spec header
**Out of scope:** any change under `src/main`; AC-9; running AC-6 on platforms other than Windows x86-64; any POM version
**Reuses:** `ImageElement.java:82-105` (8-bit min/max branch), `MediaElement.java:141-151`, `Imgcodecs.dicomJpgWrite`, dcm4che `DicomOutputStream` / `Fragments`, `maven-dependency-plugin` copy pattern (`weasis-opencv/weasis-opencv-core-windows-x86-64/pom.xml`), JUnit 4 / AssertJ / Surefire; net-new — the fakes and the fixture generator (no test double or DICOM writer exists in the cited modules' tests)
**Complexity:** complex — deterministic concurrency fixtures that must fail for the right reason, a static pool read once per JVM, native library loading in a test JVM for the first time, and generated multi-fragment fixtures
**Run note:** spec §10 item 2 — record that the `[guard]` criteria's evidence is this green run on the unchanged code at `389f939` (then again in Increment 2), not a RED run; list each criterion's result here.

**Gate record (2026-11-07):** `mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am test
-Dmaven.test.failure.ignore=true` with the main code unchanged at `389f939` (no change under `src/main`, no POM
version), the tests as committed with this record, Windows x86-64, JDK 17. `weasis-core-api`: 24 tests, 1 failure;
`weasis-dicom-codec`: 13 tests, 3 failures; the build itself succeeded (`-DskipTests package` exit 0). The stop rule
did not trigger: every `[new behaviour]` criterion failed for the reason FCR §2 *Failing fixtures* names, and every
`[guard]` criterion passed. Before the latch opens, AC-3 now asserts that seven callers are `BLOCKED` on the element's
monitor held by the eighth, and AC-8 that all 32 callers are `WAITING` behind the one held decode. AC-5 now asserts
that `dis` and `tsuid` are still set after a `close()` that returned while the decode was held.

| AC | Tag | Test | Result on the unchanged code |
|---|---|---|---|
| AC-1 | `[new behaviour]` | `ImageLoaderPoolTest.twoElementsDecodeAtTheSameTime` | **failed**: "both getImageFragment() calls in progress at the same time within 5 s: the in-progress count stayed at 1 for 5 s" |
| AC-2 | `[guard]` | `ImageLoaderSingleThreadTest.twoElementsNeverDecodeAtTheSameTime` | passed |
| AC-3 | `[guard]` | `ImageLoaderPoolTest.eightCallersOfOneElementShareOneDecode` | passed |
| AC-4 | `[new behaviour]` | `ConcurrentFrameDecodeTest`, JPEG 2000 lossless and JPEG baseline | **both failed**: 50 of 50 repetitions failed, "Cannot match all the fragments to all the frames!" 394 of 400 frames (JPEG 2000 lossless) and 400 of 400 (JPEG baseline) |
| AC-5 | `[new behaviour]` | `ReaderCloseDuringDecodeTest.closeDuringADecodeKeepsItsPixelsAndResetsTheReaderAfterIt` | **failed**: "close() returned while the decode of frame 3 was held and did not clear the state the decode reads: dis=null, tsuid=null" |
| AC-6 | `[guard]` | `ConcurrentSyntaxDecodeTest` (nine syntaxes, 8 threads × 20 rounds) | passed (serialized by the single thread) |
| AC-7 | `[guard]` | `ImageLoaderPoolTest.interruptedCallerReturnsAndALaterCallDecodes` | passed |
| AC-8 | `[guard]` | `ImageLoaderSingleThreadTest.decodesNeverRunOnACallersThread` | passed |
| AC-9 | — | — | not applicable (unbounded queue, Context *Decisions*) |
| existing | — | `TagWTest` (9), `WPropertiesTest` (10), `TagDTest` (6), `DicomMediaUtilsTest` (3) | 28 passed |

**Build record (2026-11-07):** commits `19c571cf4` (tests) and `5af657b70` (review repair: asserted contention in
AC-3 and AC-8, AC-5 checked during the decode, this gate record). Effort: high, as routed. Inline review: spec
**Pass** (one plan-deviation warning — the missing gate record — repaired); code quality **Approved** (Minor only:
formatter drift because Spotless cannot run on JDK 17, readers not closed on failure paths, a best-effort wait helper,
nits); test quality **Changes Requested** → repair iteration 1 → **Approved** (Minor: AC-8 counts any `WAITING`
caller, not only one parked on a `FutureTask`). Open advisories carried to the final gate: AC-6 uses 64×64 frames;
the Gherkin comment of AC-5 above and Risks still place AC-5's red at the post-decode check, while the test now fails
first at the during-decode check. Coverage delta: unavailable — no coverage tool is configured in the cited modules.
Deviations: Linux and macOS native profiles copy artifactId `libopencv_java` (the name the `weasis-opencv` modules
use); extra test helpers `ImageCaller`, `CodecTestEnvironment`, `SyntheticDicomFiles`; `BundleTools` needed
`weasis.pref.dir` and `weasis.resources.path` as system properties (no PowerMock); the full test run is red by design
in this increment, so it was run with `-Dmaven.test.failure.ignore=true` to see both modules.

---

### - [ ] 2. Concurrent decoding (FCR §5 task 4) — the pool and its property, queue removal on cancel, a reader safe for concurrent decodes, the released-buffer decision

**Covers:** AC-1, AC-4, AC-5 (red → green); AC-2, AC-3, AC-6, AC-7, AC-8 (green again)
**ATDD Scenarios:** Increment 1's tests, unchanged, plus:

```gherkin
Scenario: pool size from the property and the formula
  Given weasis.image.loader.threads is "1" / "6" / "0" / "abc" / unset
  When the pool size is computed with 8 processors and a 4096 MB max heap
  Then it is 1 / 6 / 16 with a warning / 16 with a warning / 16 (= max(2, min(min(16, 40), 16)))
  And with 1 processor and a 150 MB max heap, unset gives 2

Scenario: pool threads
  Given the pool is created
  When a task runs on it
  Then its thread is a daemon named "Image Loader-<n>" with n ≥ 1

Scenario: a queued task is removed when its caller is interrupted
  Given weasis.image.loader.threads=1, the worker held by E1's decode, and caller T2 waiting on E2 (E2's task queued)
  When T2 is interrupted
  Then T2 returns with its interrupt status set, the pool's queue no longer holds E2's task, and E2.isLoading() is false
  And after the latch opens, E2.getImage() from another thread returns an image
```

**Approach:**
- `ImageElement.java:41-42`: a `private static final ThreadPoolExecutor` built with core = max = the computed size,
  keep-alive 0, an unbounded `LinkedBlockingQueue`, and a factory making daemon threads `Image Loader-<n>`;
  `IMAGE_LOADER` is assigned from it (type, name and visibility unchanged). The size comes from a package-private
  static method that takes the property string, processor count and max heap as arguments (so the formula is
  unit-testable without new JVMs) and is called with `System.getProperty("weasis.image.loader.threads")`,
  `Runtime.availableProcessors()`, `Runtime.maxMemory()`.
- `ImageElement.java:418-422` and `Thumbnail.java:332-336`: on `InterruptedException`, after re-asserting the
  interrupt and `future.cancel(true)`, remove the `Future` from the private pool (`ThreadPoolExecutor.remove`,
  the `FutureTask` is the queued `Runnable`) through one package-private static helper in `ImageElement`, before
  the loading mark is cleared (`setAsLoaded()` at `:440`; `loading.set(false)` in `Thumbnail`'s `finally`). A task
  already taken by a worker finishes and its result is discarded, as today. `getImage(OpManager, boolean)` stays
  `synchronized` (`:368`).
- `DicomMediaIO.java:287`, `:1118-1131`: `fragmentsPositions` becomes a `private volatile List<Integer>` (null until
  built). The first decode that needs it builds the positions in a local list inside `synchronized (this)`
  (re-checking for null), then assigns an unmodifiable copy; later decodes read the published list without the
  lock. The size check (`:1133`) is unchanged.
- `DicomMediaIO.java:714-729`, `:1188-1206`: the public `getImageFragment(MediaElement)` increments an in-flight
  count (guarded by a private lock of the reader, `decodeLock` — *build-time deviation* from the reader's monitor
  planned here, for the reason given in Architecture) before calling the protected
  `getImageFragment(MediaElement, int)` and decrements it in `finally`; the counter wraps the protected call, so a
  subclass override sits inside it. `reset()` takes the same private lock: with decodes in flight it marks a reset
  pending and returns; the decrement that reaches zero runs `resetInternalState()` once and clears the mark. A
  `dispose()` (from `close()`) with decodes in flight marks a dispose pending as well: the decrement that reaches
  zero removes the header from `HEADER_CACHE` outside the private lock, then, under it and only if the count is
  still zero, runs `resetInternalState()` and clears both marks. The private lock is not held during the native
  decode, so decodes of one reader still run in parallel. The decode takes the transfer syntax from the header
  `readMetaData()` returns, which carries the syntax its header read found. Every method of `HEADER_CACHE`'s
  anonymous subclass that reads or changes its maps is `synchronized`. The unused `readingImage` flag (`:305`) is folded into the counter;
  `readingHeader` keeps today's behaviour. `dispose()` and `close()` keep their signatures.
- **Released-buffer guard (FCR §2 item 5; spec §8 Q4):** `PlanarImage` in `weasis-core-img` 4.5.3 declares no
  method that reports a released buffer (only `release()`, `close()`, `physicalBytes()`, sizes and `get`/
  `assignTo`; `ImageCV extends Mat`). Check upstream `v4.7.2`'s `isBufferReleased` against that API: if it needs a
  method 4.5.3 lacks, leave the guard out and say so, with that evidence, in the commit message and in this
  increment's record; if it can be written against `Mat` alone, port it with a test that evicts the cache entry
  between decode and return.
- Run the quality gates of `CLAUDE.md`; all Increment 1 tests and the 28 existing tests pass. Run AC-6 on this
  machine in a **foreground** session (FCR §5).

**Affected files:** `weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/ImageElement.java`,
`weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/Thumbnail.java`,
`weasis-dicom/weasis-dicom-codec/src/main/java/org/weasis/dicom/codec/DicomMediaIO.java`, the new tests above in
`weasis-core/weasis-core-api/src/test/java/org/weasis/core/api/media/data/`
**Depends on:** Increment 1, with its gate result recorded and no stop
**Out of scope:** a bounded queue or `CallerRunsPolicy`; a per-element future that later callers join; any public or protected signature change; `ThreadUtil`'s existing builders; any other module; any POM version
**Reuses:** `ImageElement.java:409-443`, `Thumbnail.java:314-336`, `MediaElement.java:141-151`, `DicomMediaIO.java:714-729`, `:1110-1150`, `:1180-1206`, `:1217`, `ThreadPoolExecutor` / `LinkedBlockingQueue` / `remove(Runnable)`; net-new — the daemon `Image Loader-<n>` thread factory (`ThreadUtil.getThreadFactory` names and daemon status do not fit FCR §2 item 1)
**Complexity:** complex — changes the process-wide decode executor and the reader's reset semantics, a compatibility boundary for every image load, with lock and interrupt interactions
**Run note:** spec §10 item 2 — record the green run after the change for every criterion, and whether the build accepted the evidence rule as written or asked for something else.

**Gate record (2026-11-07):** first, AC-1, AC-4 and AC-5 were run again on the unchanged main code and failed as
recorded in Increment 1. After the change, `mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec
-am -DskipTests package` exited 0, then `mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am
test` exited 0 three times in a row, in a foreground session on Windows x86-64 with JDK 17. Each run:
`weasis-core-api` 34 tests and `weasis-dicom-codec` 13 tests, with no failure, error or skip. Increment 1's tests are
unchanged. The build accepted the evidence rule as written: each `[guard]` criterion's evidence is a green run before
the change (Increment 1) and the green runs after it. Each `[new behaviour]` criterion failed before the change and
passes after it. Each new scenario was seen failing before its code was written.

| AC / scenario | Tag | Test | Result after the change (3 runs) |
|---|---|---|---|
| AC-1 | `[new behaviour]` | `ImageLoaderPoolTest.twoElementsDecodeAtTheSameTime` | passed |
| AC-2 | `[guard]` | `ImageLoaderSingleThreadTest.twoElementsNeverDecodeAtTheSameTime` | passed |
| AC-3 | `[guard]` | `ImageLoaderPoolTest.eightCallersOfOneElementShareOneDecode` | passed |
| AC-4 | `[new behaviour]` | `ConcurrentFrameDecodeTest`, JPEG 2000 lossless and JPEG baseline | passed: 50 of 50 repetitions, 400 of 400 frames identical to the serial decode, for each syntax |
| AC-5 | `[new behaviour]` | `ReaderCloseDuringDecodeTest.closeDuringADecodeKeepsItsPixelsAndResetsTheReaderAfterIt` | passed |
| AC-6 | `[guard]` | `ConcurrentSyntaxDecodeTest` (nine syntaxes, 8 threads × 20 rounds) | passed: 160 of 160 decodes pixel-identical for each syntax, decoded concurrently on the pool, Windows x86-64 |
| AC-7 | `[guard]` | `ImageLoaderPoolTest.interruptedCallerReturnsAndALaterCallDecodes` | passed |
| AC-8 | `[guard]` | `ImageLoaderSingleThreadTest.decodesNeverRunOnACallersThread` | passed |
| AC-9 | — | — | not applicable (unbounded queue, Context *Decisions*) |
| pool size | new | `ImageLoaderPoolSizeTest` (6 tests) | before its code: 5 of 6 failed, because the size was always 1; after: passed |
| pool threads | new | `ImageLoaderThreadsTest.tasksRunOnDaemonThreadsNamedImageLoaderN` (property `3`) | before its code: failed, "3 tasks running at the same time within 5 s"; after: passed |
| queued task removed | new | `ImageLoaderQueuedCancelTest.queuedTaskIsRemovedWhenItsCallerIsInterrupted` (property `1`) | before its code: failed, "tasks left in the queue after the interrupt expected 0 but was 1"; after: passed |
| released buffer | new (FCR §2 item 5) | `ReleasedBufferTest` (2 tests) | before its code: both failed (1 decode instead of 2; element made unreadable); after: passed |
| existing | — | `TagWTest` (9), `WPropertiesTest` (10), `TagDTest` (6), `DicomMediaUtilsTest` (3) | 28 passed |

*Released-buffer guard (spec §8 Q4):* ported. Upstream `v4.7.2`'s `isBufferReleased(PlanarImage)` is
`image != null && image.width() <= 0`, and `PlanarImage.width()` exists in `weasis-core-img` 4.5.3. A probe on this
machine, with `weasis-core-img` 4.5.3 and the native library `4.5.3-dcm`, showed an `ImageCV` of 3×4 reporting
`width=0 height=0` after `release()`. The guard decodes again, once, when the cache entry was evicted between the decode
and the return. It keeps the element readable when the buffer is released while the minimum and maximum are computed.
*Shutdown:* nothing in `weasis-core-api` or `weasis-dicom-codec` joins, shuts down or awaits `IMAGE_LOADER`; its only
users are `ImageElement` and `Thumbnail`, which submit to it. The only `awaitTermination` / `shutdownNow` in the cited
modules is `GuiExecutor`, which throws `UnsupportedOperationException` from them.
*Deviation:* the in-flight count and the pending reset are guarded by a private lock of the reader, not by its
monitor. `readMetaData()` holds the monitor during header I/O. Its `HEADER_CACHE.put` can reach another reader's
`reset()` through the soft cache's removal callback, so a `reset()` on the monitor could deadlock two readers. With the
private lock, `reset()` never waits for a header read. The fragment index is still built under the reader's monitor.
*Final-review repair (2026-11-07):* a `close()` during a decode removed the header from `HEADER_CACHE` at once and
deferred only the reset, so the decode cached the header again and the deferred reset then left a cached header with
`tsuid` and `dis` cleared: the next decode of another frame of a multi-frame RLE lossless reader returned wrong
pixels. Now the whole `dispose()` is deferred (header removal outside the private lock, then the reset), and the decode
takes the transfer syntax with its header read under the monitor. AC-5 gains a second scenario in
`ReaderCloseDuringDecodeTest` (RLE lossless, 8 frames): after the decode held during `close()` ends, the header is no
longer cached and frame 4 decoded on the same reader equals its undisturbed decode; it failed on the code before the
repair (both checks) and passes after. The first scenario is unchanged. `weasis-dicom-codec` now runs 14 tests.
*Second final-review repair (2026-11-07):* (1) a header eviction during a decode (the soft cache's removal callback
removes the header, then calls `reset()`, which the decode defers) left the same inconsistency by another path: the
decode, released, read the header again and cached it, and the deferred reset then cleared `tsuid`, so the next
decode of another frame of a multi-frame RLE lossless reader read a null transfer syntax from the cached header and
returned no pixels. The reader now caches each header read from the file with the transfer syntax that read found,
and the decode takes it from the header `readMetaData()` returns, so no reset, deferred or not, can separate the two;
the reset still clears the reader's parse state as before. The header re-read after the eviction stays cached, as on
the unchanged code. AC-5 gains a third scenario in `ReaderCloseDuringDecodeTest`
(`headerEvictedDuringADecodeLeavesTheReaderReadyToDecodeAnotherFrame`, RLE lossless, 8 frames, frame 3 held while
the removal callback runs with the cached reference): it failed on the code before this repair ("header cached=true,
tsuid=null", frame 4 decoded empty) and passes after. (2) `HEADER_CACHE` was read and changed from several threads
with no common lock; every method of its anonymous subclass that reads or changes the maps is now `synchronized`
(lock order in Architecture). `HeaderCacheLockTest` (3 tests) starts each operation (a header read, a `close()`,
and `get`, `put`, `remove`, `removeElement`, `expungeStaleEntries`, `containsKey`, `size`, `entrySet`, `clear`) while
the test holds the cache's monitor and checks that it waits for it: all three failed before the change ("waited for
the header cache's monitor" was false) and pass after. `ImageLoaderPoolSizeTest` gains `heapBoundsTheSize` and
`processorsBoundTheSize`, which pin the existing formula and passed at once. The first two AC-5 scenarios are
unchanged. `weasis-core-api` now runs 36 tests and `weasis-dicom-codec` 18.

---

### - [ ] 3. Internal version (FCR §5 task 5) — hand-off to the release half

**Executed by:** the release half (`weasis-fork-patch`, release). **`abs.build` does not execute this increment.**
**Covers:** AC-10
**ATDD Scenarios:**

```gherkin
Given Increments 1 and 2 are checked and green on change/concurrent-image-decoding
When the release half sets revision to 3.8.2 with an empty changelist in weasis-parent/pom.xml
Then mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am package produces weasis-core-api-3.8.2.jar and weasis-dicom-codec-3.8.2.jar
And no module POM other than weasis-parent/pom.xml changed
```

**Approach:** The release step's, once (`CLAUDE.md` rule 6; `architecture.md` *Branch and version rule*). Until
then the branch keeps `3.8.1`; every module resolves its siblings through `${project.version}` inside the reactor,
so the tests of Increments 1, 2 and 4–6 exercise the changed code without the version change. Do not
`mvn install` the branch before the version is set: it would overwrite the upstream `3.8.1` artifacts in the local
repository.
**Affected files:** `weasis-parent/pom.xml:17-18` (by the release half only)
**Depends on:** Increment 2
**Out of scope:** any other POM; tagging and publishing (FCR §5 task 7)
**Reuses:** `weasis-parent/pom.xml:17-18` (`revision` / `changelist`)
**Complexity:** trivial — one property value, inherited by every module
**Run note:** none of spec §10's items; spec §8 Q5 (FCR §5 order vs the release procedure's order) is the release approver's.

---

### - [ ] 4. Native thread-safety on Windows x86-64 (FCR §5 task 6) — this machine

**Executed by:** the operator on this machine (Windows 11, x86-64), in a foreground session.
**Covers:** AC-6 (platform run), AC-11 (Windows x86-64)
**ATDD Scenarios:**

```gherkin
Given the branch at the commit Increment 3 produced, JDK 17 x86-64, and the windows-x86-64 profile active
When mvn -B -pl weasis-dicom/weasis-dicom-codec -am test -Dtest=ConcurrentSyntaxDecodeTest -Dsurefire.failIfNoSpecifiedTests=false runs in a foreground terminal
Then all 9 syntaxes are pixel-identical to their serial decodes over 20 rounds from 8 threads
And the native library loaded is opencv_java-4.5.3-dcm-windows-x86-64
```

**Approach:** Run the command; keep the Surefire report. **Stop** (FCR §5 task 6) if any syntax decodes differently
under concurrency: report back; nothing is released.
**Affected files:** none (a record only)
**Depends on:** Increment 3
**Out of scope:** timing measurements beyond what FCR §5 asks; other platforms
**Reuses:** `ConcurrentSyntaxDecodeTest` from Increment 1
**Complexity:** standard — runs an existing test; the risk is a real native-codec finding
**Run note:** spec §10 item 4 — the result, where it is recorded, by which role, on which date.

---

### - [ ] 5. Native thread-safety on Linux x86-64 (FCR §5 task 6) — the dual-boot notebook

**Executed by:** the operator on the notebook, booted into Linux.
**Covers:** AC-6 (platform run), AC-11 (Linux x86-64)
**ATDD Scenarios:**

```gherkin
Given a clone of change/concurrent-image-decoding at the same commit as Increment 4, JDK 17 x86-64, Maven 3.9.x, and uname -m = x86_64
When mvn -B -pl weasis-dicom/weasis-dicom-codec -am test -Dtest=ConcurrentSyntaxDecodeTest -Dsurefire.failIfNoSpecifiedTests=false runs
Then the linux-x86-64 profile copied libopencv_java.so (4.5.3-dcm) and all 9 syntaxes are pixel-identical over 20 rounds from 8 threads
```

**Approach:** Clone or fetch the branch; check `uname -m` first (if it is not `x86_64`, record it and treat the
platform under Increment 7); run; keep the report. Stop rule as Increment 4.
**Affected files:** none (a record only)
**Depends on:** Increment 4
**Out of scope:** Linux x86 (32-bit), armv7a, aarch64
**Reuses:** `ConcurrentSyntaxDecodeTest`, the `linux-x86-64` profile from Increment 1
**Complexity:** standard — first run of the native profile on Linux; dependency resolution of the `.so` is new there
**Run note:** spec §10 item 4.

---

### - [ ] 6. Native thread-safety on macOS x86-64 (FCR §5 task 6) — Apple Silicon Mac through Rosetta 2

**Executed by:** the operator on the Mac.
**Covers:** AC-6 (platform run), AC-11 (macOS x86-64)
**ATDD Scenarios:**

```gherkin
Given Rosetta 2 installed, an x86-64 JDK 17 (java -XshowSettings:properties shows os.arch = x86_64), and Maven run on that JDK
When mvn -B -pl weasis-dicom/weasis-dicom-codec -am test -Dtest=ConcurrentSyntaxDecodeTest -Dsurefire.failIfNoSpecifiedTests=false runs
Then the mac profile copied libopencv_java.jnilib (macosx-x86-64, 4.5.3-dcm) and all 9 syntaxes are pixel-identical over 20 rounds from 8 threads
```

**Approach:** Weasis 3.8.1 ships no Apple Silicon native library (`weasis-opencv/pom.xml:19-25`), so the shipped
platform is macOS x86-64, reached on this Mac through Rosetta 2 with an x86-64 JDK (`JAVA_HOME` pointing at it, so
Maven's profile activation sees `x86_64`). An arm64 JDK would match no profile and the test fails by design. Record
that the run was under Rosetta 2 on Apple Silicon. Stop rule as Increment 4.
**Affected files:** none (a record only)
**Depends on:** Increment 5
**Out of scope:** an Apple Silicon native library
**Reuses:** `ConcurrentSyntaxDecodeTest`, the `mac` profile from Increment 1
**Complexity:** standard — the platform is reached through translation, which the record must state
**Run note:** spec §10 item 4.

---

### - [ ] 7. Platforms with no machine (FCR §5 task 6) — exceptions by the release approver

**Executed by:** the release approver (role and date only, never a name).
**Covers:** AC-11 (Windows x86, Linux x86, Linux armv7a, Linux aarch64)
**ATDD Scenarios:**

```gherkin
Given Increments 4–6 are recorded green
When the release approver reviews Windows x86, Linux x86, Linux armv7a and Linux aarch64
Then each has either a recorded green run of ConcurrentSyntaxDecodeTest or a recorded exception with its reason, role and date
```

**Approach:** Record one line per platform in the spec's header *Verified* row and in spec §10 item 4; this closes
spec §8 Q1. A platform the product does not ship on is a valid reason.
**Affected files:** `docs/specs/concurrent-image-decoding.md` (header *Verified*, §10 item 4)
**Depends on:** Increment 6
**Out of scope:** emulated runs
**Reuses:** the spec's *Verified* row and §10 item 4
**Complexity:** trivial — a record, no code
**Run note:** spec §10 item 4.

## Context Loading Order

1. `CLAUDE.md`, `architecture.md` — repository rules, modules, commands, the facts at `389f939`.
2. `docs/specs/concurrent-image-decoding.md` — §2 criteria, §6 constraints, and the FCR at its end (§2 *Concurrency contract*, §3, §5).
3. This plan — Context *Decisions*, the increment being executed.
4. `ImageElement.java`, `MediaElement.java:141-151`, `Thumbnail.java:228-241`, `:314-345`, `MediaReader.java`, `NativeCache.java` (weasis-core-api); `DicomMediaIO.java:280-310`, `:700-730`, `:810-920`, `:1060-1350` (weasis-dicom-codec).
5. `weasis-core-img` 4.5.3 sources: `PlanarImage`, `ImageCV`, `Imgcodecs` (local repository).
6. Volatile: the test classes from Increment 1 and their recorded results; the diff since `389f939`.

## Risks

| Risk | Affects | Mitigation |
|------|---------|------------|
| A concurrency test passes or fails for the wrong reason (timing), which would mislead the task-3 gate | 1, 2 | Latches and barriers instead of sleeps; bounded waits that fail with a message; AC-1's red is "count stayed 1 for 5 s", checked against FCR §2 *Failing fixtures* before recording |
| Another test class loads `ImageElement` first in a shared JVM, fixing the pool size | 1, 2 | Surefire `reuseForks=false` in `weasis-core-api`; each class sets its property in a `static {}` block, before any reference to `ImageElement` |
| `DicomMediaIO` / `DicomImageElement` reach OSGi-only statics (preferences, bundle context) outside a framework, which is why the existing codec tests use PowerMock | 1 | Increment 1 starts with a smoke test that opens one generated file and decodes one frame outside OSGi; if a static needs a framework, stub it in the test (PowerMock is already a test dependency) and record which one |
| The native library cannot be loaded in the test JVM (wrong classifier, missing download) | 1, 4, 5, 6 | OS/arch profiles; the loader fails with `os.name` / `os.arch`; verified first on Windows x86-64 in Increment 1 |
| `Imgcodecs.dicomJpgWrite` cannot produce a syntax (e.g. JPEG-LS near-lossless) or a split codestream is not recognised as one frame | 1 | Check each generated fixture by a serial decode first; if a syntax cannot be generated, stop and ask before committing a fixture (no patient data, generator kept with the test) |
| AC-5 does not go red on the unchanged code because `readMetaData()` reopens the stream mid-decode | 1 | The red comes first from the check made after `close()` returns while the decode is still held (`dis` and `tsuid` already cleared under the decode), before the post-decode check is reached; confirm the failure message names that check |
| Lock interplay: the cache eviction calls `reader.close()` (`ImageElement.java:51-55`) while a decode holds the reader's monitor in `readMetaData()` | 2 | *Build-time deviation:* `reset()` and `dispose()` take a private lock of the reader (`decodeLock`), not its monitor, only briefly to read the count and mark a reset or dispose pending, so they never wait for a header read; no other lock is taken under it, and `HEADER_CACHE` (whose removal callback runs another reader's `reset()`) is only called outside it, so no two private locks nest. `HEADER_CACHE` holds its own monitor in every method that reads or changes its maps. The lock order is the `ImageElement` monitor → `NativeCache.hash` → `HEADER_CACHE`'s monitor → a reader's `decodeLock`, and a reader's monitor → `HEADER_CACHE`'s monitor → a reader's `decodeLock`; under `HEADER_CACHE`'s monitor only the evicted reader's `reset()` takes a lock (its `decodeLock`), and under a `decodeLock` none is taken, so no path takes them in reverse. AC-5 (three scenarios: close during a decode twice, header evicted during a decode) and AC-4 run these paths; `HeaderCacheLockTest` checks that every method of the cache waits for its monitor |
| Daemon pool threads change shutdown behaviour (today's thread is non-daemon) | 2 | Required by FCR §2 item 1; nothing in the cited modules joins `IMAGE_LOADER` at shutdown (check with a search in Increment 2) |
| Memory pressure from several concurrent decodes | 2 | Upstream formula caps threads at `maxHeapMB / 100`; the out-of-memory retry (`ImageElement.java:371-378`) is unchanged; `weasis.image.loader.threads=1` is the field fallback |
| A Rosetta 2 run is not taken as evidence for macOS x86-64 | 6, 7 | The record states the translation; the release approver accepts it or records an exception |

## Open Questions

- **Spec §8 Q1** (platforms this machine cannot run) — planned: Increments 5 and 6 cover Linux x86-64 and macOS
  x86-64; Increment 7 records exceptions for the other four. Closes when Increments 4–7 are recorded.
- **Spec §8 Q4** (released-buffer guard on `weasis-core-img` 4.5.3) — the API read at planning shows no released-buffer
  query on `PlanarImage`; Increment 2 confirms against upstream `v4.7.2` and records the answer with its evidence.
- **Spec §8 Q5** (FCR §5 order vs the release procedure's order) — the release approver's; this plan keeps FCR §5's
  order (version, Increment 3, before the platform checks, Increments 4–7).
- **Spec §8 Q6** (native codec in the test JVM; fixture origin) — planned in Increment 1 (OS profiles, fixtures
  generated in the test); closes when Increment 1 records it.
- Spec §8 Q2 and Q3 are closed by the planning decisions in Context.
