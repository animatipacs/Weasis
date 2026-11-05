# Change spec: decode images concurrently instead of on one process-wide thread (3.8.x)

This spec maps the signed fork change request (FCR) onto the nine sections the planning skill reads, so that
`abs.plan` ingests it unchanged and `abs.build` builds it. The FCR is the spec: every section below points at the
FCR's own text, which follows verbatim at the end, after the run notes of §10.

| Field | Value |
|---|---|
| **Change** | Decode images concurrently instead of on one process-wide thread (3.8.x) |
| **Written by** | `weasis-fork-patch` (the fork spec half), 2026-11-05 |
| **Baseline gate (FCR §5 task 1)** | outcome: **every artifact matches the tag** · selected commit `389f93921da6b1d3658f545d64a64437d4af032f` (tag `v3.8.1`). The requester ran the steps specific to the downstream product's repository; this run received only the outcome and the commit. The FCR's §0.1 already carries the outcome (*Outcome (2026-11-05, operator)*), so the copy below is unchanged. |
| **Branch** | `change/concurrent-image-decoding` from `389f939`; the release branch `3.8.x` (head `412c16a`) untouched |
| **Declared version** | `3.8.1` — `revision` at `weasis-parent/pom.xml:17`, empty `changelist` at `:18`, at the selected commit. The release half sets the internal version; this spec changes no POM |
| **Context layer** | `architecture.md`, `CLAUDE.md`, `docs/specs/`, `docs/plans/` — written in this run (none was present) |
| **Citations** | Every `path:line` of FCR §1–§3 read at `389f939` on 2026-11-05: each says what the FCR says |
| **Scan** | deny list of 4016 names · probe → 2 hits (exit 1) · the FCR copy, this file and the context layer → 0 hits (exit 0), 2026-11-05 |
| **Plan** | `docs/plans/concurrent-image-decoding.md` — written by `abs.plan`; its increments follow FCR §5 in order |
| **Verified** | — §3 re-run on the build: *(the release half)*; platform checks: *(the release half; §8 row 1)* |
| **Release approval** | — *(the release half: role and date only, never a name)* |
| **Released** | — *(the release half: version · tag · date)* |
| **Status** | `approved senior software architect, 2026-10-29` — copied from the FCR's public-safe sign-off row: role and date only |

## 1. Intent

Today every pixel decode Weasis performs through `ImageElement.getImage()` and through thumbnails is served by one
process-wide single-thread executor, `ImageElement.IMAGE_LOADER`, so callers that load a series from several
threads gain nothing, and two latent races in `DicomMediaIO` (the lazy fragment index and a reset during a decode)
stay hidden only because decodes never overlap (FCR §1). The change makes `IMAGE_LOADER` a bounded multi-thread
pool of daemon threads whose size a system property can set (`1` restores today's behaviour exactly), whose
backpressure never drops a task and never runs a decode on the submitting thread, and whose interrupted callers
leave no queued task and no stale loading mark; it makes `DicomMediaIO` safe for concurrent decodes of the same
reader; it ports upstream's released-buffer guard only if it applies on 3.8.x; and it keeps every public and
protected signature, the per-element single decode, the cache semantics, the out-of-memory handling and the
decoded pixel values (FCR §2 and its *Concurrency contract*).

**Success:** every `[new behaviour]` criterion of §2 below fails on the unchanged code and passes after the
change; every `[guard]` criterion passes before and after; the internal version is set across the family; the
platform checks are recorded; the leak scan is clean; the release is approved.

## 2. User Story & AC

*As the downstream viewer FCR §4 describes, I want `getImage()` calls on different image elements to decode in
parallel on a bounded pool of Weasis threads, with the same results and guarantees as today, so that the
per-instance calls of one volume, issued from several worker threads, decode in parallel instead of queuing on a
single thread.*

**Tags.** FCR §3 does not tag its criteria inline; the tags below are read from FCR §2 *Concurrency contract*,
row *Failing fixtures, per §3 criterion*, and from the stop rule of FCR §5 task 3, which name the criteria that
fail on the unchanged code (1, 4, 5) and the ones that pass there by design (2, 3, 7, 8). Criterion 6 pins the
decoded pixel values that FCR §2 item 3 says must not change; criterion 9 exists only with a bounded queue.

| # | Tag | Source | On the unchanged code (FCR §2 *Failing fixtures*, §5 task 3) — or, for a release criterion, its text |
|---|---|---|---|
| AC-1 | `[new behaviour]` | FCR §3 (1) | fails: one thread serves both calls |
| AC-2 | `[guard]` | FCR §3 (2) | passes by design |
| AC-3 | `[guard]` | FCR §3 (3) | passes by design |
| AC-4 | `[new behaviour]` | FCR §3 (4) | fails: concurrent first decodes fill the lazy fragment index together |
| AC-5 | `[new behaviour]` | FCR §3 (5) | fails: `close()` clears `tsuid` and `dis` under the running decode |
| AC-6 | `[guard]` | FCR §3 (6) | passes, serialized by the single thread; it checks the native codec only once the pool exists |
| AC-7 | `[guard]` | FCR §3 (7) | passes by design |
| AC-8 | `[guard]` | FCR §3 (8) | passes by design |
| AC-9 | `[new behaviour]`, only if the queue is bounded | FCR §3 (9) | cannot be set up (submission never blocks); written together with the bounded queue, if one is chosen |
| AC-10 | release | FCR §5 task 5 | The internal version `3.8.2` is set in the parent's `revision` and inherited by every module of the family |
| AC-11 | release | FCR §5 task 6 | Every platform check FCR §5 task 6 names has a recorded result, or an exception recorded by the release approver |
| AC-12 | release | the FCR's banner; FCR §5 task 7 | The diff, every commit message since the branch point and the documents under `docs/` and the context layer scan clean with the deny list |

The criteria, **FCR §3 verbatim** (its preamble: *Criteria 1–3 and 7–9 use a fake `MediaReader` whose
`getImageFragment()` blocks on a shared latch and then returns a small non-null image; they need no native
library. Criteria 4–6 use DICOM fixtures and the native codec.*):

- **AC-1** — **Given** a pool size of 2 or more and two `ImageElement`s backed by the blocking fake reader, **when**
  `getImage()` is called on each from its own thread, **then** both `getImageFragment()` calls are in progress at
  the same time before either returns.
- **AC-2** — **Given** the pool-size property set to `1`, **when** the scenario of criterion 1 runs, **then** the two
  `getImageFragment()` calls never overlap.
- **AC-3** — **Given** one `ImageElement`, the fake reader returning one and the same non-null image instance, and eight
  threads that pass a shared start barrier immediately before calling `getImage()` on it, **when** the latch is
  released only after the barrier has opened and the first `getImageFragment()` call is in progress, and they all
  return, **then** `getImageFragment()` ran exactly once and every caller received that image instance.
- **AC-4** — **Given** a fresh multi-frame JPEG 2000 file and a fresh multi-frame JPEG file, each with several fragments per
  frame, **when** all frames are decoded concurrently from eight threads, **then**:
  - every frame decodes, with pixels equal to a serial decode;
  - no `Cannot match all the fragments to all the frames!` is raised.

  **All frames of one repetition decode through the same `DicomMediaIO` instance**, because the race lives in that
  reader's lazy fragment index. Each repetition starts from a new reader. Repeat at least 50 times.
- **AC-5** — **Given** a multi-frame reader with one frame being decoded, **when** `close()` is called on the same reader
  from another thread during that decode, **then**:
  - the decode completes with the same pixels as an undisturbed decode;
  - once it has ended, the reset has run: the reader's stream is closed and its parse state is cleared, exactly
    as after a `close()` with no decode in flight.
- **AC-6** — **Given** single-frame fixtures in each supported syntax, **when** they are decoded concurrently from eight
  threads, 20 rounds each, **each thread through its own `ImageElement` and reader for the file** (so the
  per-element guard that criterion 3 pins cannot serialize them), **then** every result is pixel-identical to a
  separate serial decode of the same file. The
  syntaxes are: explicit VR little endian, deflated, RLE lossless, JPEG baseline, JPEG lossless, JPEG-LS lossless
  and near-lossless, and JPEG 2000 lossless and lossy. This criterion checks that the bundled native codec is
  thread-safe at `weasis.opencv.native.version` `4.5.3-dcm`.
- **AC-7** — **Given** a caller blocked in `getImage()` on the fake reader, **when** that caller thread is interrupted,
  **then**:
  - the call returns with the thread's interrupt status set, while the latch is still closed;
  - after that, the test releases the latch, and a later `getImage()` on the same element from another thread
    decodes it successfully. With a pool size of 2 or more, its `getImageFragment()` may overlap the abandoned one
    (§2 item 1); the test must not fail on that overlap.
- **AC-8** — **Given** the pool size set to 1 and many more callers than pool threads (with a bounded queue, also more than
  the queue can hold), each on its own element backed by the blocking fake reader, **when** they all call
  `getImage()`, **then** every `getImageFragment()` runs on a pool thread, never on a caller's thread. The fake
  reader records `Thread.currentThread()`.
- **AC-9** — **Only if the queue is bounded.** **Given** the pool size set to 1, its worker held by the blocking fake reader
  and the queue full, **when** one more caller calls `getImage()` on its own element and is interrupted while its
  submission waits for capacity, **then**:
  - the call returns with the thread's interrupt status set;
  - no task for that element is left in the queue;
  - once the latch is released, a later `getImage()` on the same element from another thread decodes it
    successfully.

(Inside the criteria, "criterion *n*" means AC-*n*, and "§2 item 1" means FCR §2 item 1.)

## 3. User-Facing Behavior

`N/A — no user interface changes; the change is behavioural, pinned by the Weasis tests of §2.`

The scenarios are FCR §3's criteria as Given / When / Then, in the same order; their full text is §2 above.
**Evidence rule:** a `[guard]` criterion's evidence is **the green run on the unchanged code at the selected
commit, then again after the change — not a RED run**; a `[new behaviour]` criterion **fails on the unchanged code
first**, in the way FCR §2's *Failing fixtures* row describes, and passes after the change.

- **Scenario 1 — AC-1** (`[new behaviour]`): *Given* pool size ≥ 2 and two elements on the blocking fake reader
  *When* each calls `getImage()` from its own thread *Then* both `getImageFragment()` calls are in progress at once.
  RED on the unchanged code: the second call never starts while the first blocks.
- **Scenario 2 — AC-2** (`[guard]`): *Given* the pool-size property `1` *When* scenario 1 runs *Then* the two calls
  never overlap. Green before and after.
- **Scenario 3 — AC-3** (`[guard]`): *Given* one element, one shared image instance and eight callers behind a start
  barrier *When* the latch opens after the first call is in progress *Then* one `getImageFragment()` ran and every
  caller got that instance. Green before and after.
- **Scenario 4 — AC-4** (`[new behaviour]`): *Given* fresh multi-frame JPEG 2000 and JPEG files with several fragments
  per frame, one new reader per repetition *When* all frames decode from eight threads, 50 repetitions or more
  *Then* every frame equals a serial decode and the fragment-mismatch error never appears. RED on the unchanged
  code: the error is raised.
- **Scenario 5 — AC-5** (`[new behaviour]`): *Given* a multi-frame reader decoding one frame *When* `close()` runs
  from another thread *Then* the decode's pixels equal an undisturbed decode, and afterwards the stream is closed
  and the parse state cleared. RED on the unchanged code: the state is cleared under the running decode.
- **Scenario 6 — AC-6** (`[guard]`): *Given* single-frame fixtures in each listed syntax *When* eight threads decode
  them, 20 rounds, each through its own element and reader *Then* every result is pixel-identical to a serial
  decode. Green before (serialized) and after (concurrent); the run after the change is the one that checks the
  native codec, on every platform of FCR §5 task 6.
- **Scenario 7 — AC-7** (`[guard]`): *Given* a caller blocked in `getImage()` *When* it is interrupted *Then* it returns
  with its interrupt status set while the latch is closed, and a later call from another thread decodes the
  element. Green before and after.
- **Scenario 8 — AC-8** (`[guard]`): *Given* pool size 1 and many more callers than threads (and than the queue holds,
  if bounded) *When* all call `getImage()` *Then* every `getImageFragment()` runs on a pool thread. Green before and
  after.
- **Scenario 9 — AC-9** (`[new behaviour]`, only if bounded): *Given* pool size 1, its worker held and the queue full
  *When* one more caller is interrupted while its submission waits *Then* it returns with its interrupt status set,
  no task for its element is queued, and a later call decodes the element. It cannot be set up on the unchanged
  code (FCR §5 task 3); it is written together with the bounded queue, and the plan records its evidence on that
  increment.

## 4. Mockups

`N/A — no UI in this change.`

## 5. NFRs

- **The concurrency contract of FCR §2**, every row: backpressure (waits, never dropped, never on the submitting
  thread; not `CallerRunsPolicy`), queue bound (a cancelled task is removed from the queue through the private
  `ThreadPoolExecutor` reference), interruption and cancellation (not yet submitted, queued, running), invariants
  after an interrupt, and thread confinement (daemon threads `Image Loader-<n>`; `getImage(OpManager, boolean)`
  stays `synchronized`; no new delivery to the event-dispatch thread).
- **Platform checks (FCR §5 task 6):** criterion 6 on every platform the bundled native library is shipped for.
  This machine runs Windows x86-64 only; the other platforms the build declares (`weasis-opencv/pom.xml:19-25`:
  Windows x86, Linux x86, x86-64, armv7a and aarch64, macOS x86-64) are open for the release approver (§8 row 1).
  FCR §5 records a Windows x86-64 pre-check outside Weasis and lists what it still owes. A timing measurement on
  Windows runs in a foreground session (FCR §5).
- Tests run with the module commands `architecture.md` names; no network, no external service.

## 6. Architectural Constraints

- **The contract and the assumptions of FCR §2** are the design; the planner does not reopen them. Where the
  *Concurrency contract* table and FCR §2 items 1–4 differ, items 1–4 govern (FCR §2).
- **Only the modules the FCR cites change**: `weasis-core-api` (`weasis-core/weasis-core-api/pom.xml`) and
  `weasis-dicom-codec` (`weasis-dicom/weasis-dicom-codec/pom.xml`), from `architecture.md`; `weasis-parent`
  (`weasis-parent/pom.xml`) changes only in the release half, for the version.
- **Every public and protected signature** of `ImageElement`, `MediaElement`, `Thumbnail` and `DicomMediaIO`, and
  the field `public static final ExecutorService IMAGE_LOADER`, stay as they are (FCR §2 item 3).
- **The plan's order is FCR §5**: `abs.plan` maps its increments onto §5's tasks from task 3 onward — task 3 (the
  acceptance tests of FCR §3, run on the unchanged code under its stop rule), then task 4 (the implementation of
  FCR §2) — in that order, and never reorders or merges across a gate. Tasks 1 and 2 are already recorded in this
  header. Tasks 5 (the version), 6 (the platform checks) and 7 (the leak scan and the release) belong to the
  release half, which keeps that order (§8 row 5).
- **The release branch is untouched**; the work lands on `change/concurrent-image-decoding`.
- **Repository rules**: `CLAUDE.md` at the root (Weasis terms only; tests first; the cited modules only).

## 7. Non-Goals

- Adopting the release downstream and measuring the gain there (FCR §4; FCR §5 task 7: "Pinning it on the
  consumer's side, and measuring the gain there, are not part of this request").
- The 23 commits `3.8.x` carries after the tag (FCR §0): a branch cut at `389f939` carries none of them.
- Any module the FCR does not cite; any behaviour FCR §2 does not name; a per-element future that later callers
  join (FCR §2 item 1).
- The version bump, the platform matrix and the release themselves: the release half, after the build.

## 8. Open Questions

| # | Question | Tag | Owner | Closes when |
|---|---|---|---|---|
| 1 | Criterion 6 (FCR §5 task 6) on the platforms this machine cannot run: Windows x86, Linux x86, x86-64, armv7a and aarch64, macOS x86-64 — and which of them the release must cover | `Implementation-choice` | the release approver | each platform's result is recorded, or an exception is recorded |
| 2 | Bounded queue with a blocking, interruptible submission, or an unbounded queue as today (FCR §2 item 1, *Backpressure*); AC-9 exists only with a bounded queue, and AC-8's caller count depends on it | `Implementation-choice` | developer | the task-4 increment that makes it |
| 3 | The pool-size formula ("in the same spirit as upstream `v4.6.3`") and the property's name (FCR §2 item 2 gives `weasis.image.loader.threads` as an example) | `Implementation-choice` | developer | the task-4 increment that makes it |
| 4 | Whether `PlanarImage` in `weasis-core-img` `4.5.3` can tell that its native buffer was released, so the upstream `v4.7.2` guard applies (FCR §2 item 5); if not, the change says so | `Implementation-choice` | developer | the task-4 increment records the answer with its evidence |
| 5 | FCR §5 orders the version (task 5) before the platform checks (task 6); the release procedure lists its platform checks before it sets the version. The release half keeps FCR §5's order, or the release approver records why the order does not matter | `Implementation-choice` | the release approver | the release half's record says which order ran |
| 6 | How the tests of criteria 4–6 load the native codec in the `weasis-dicom-codec` test JVM (its tests do not load it at `389f939`), and where their fixtures come from — generated in the test or committed, containing no patient data | `Implementation-choice` | developer | the task-3 increment that writes those tests |

## 9. Links

- The fork change request: the section *Fork change request (verbatim)* below.
- The plan `abs.plan` writes: `docs/plans/concurrent-image-decoding.md`.
- `architecture.md`, `CLAUDE.md` at the root (the context layer).

## 10. Run notes

Whoever runs the plan, the build and the release fills in these four items as they go, in Weasis terms only: no
downstream product, line of work, tracker card or host is named here. `abs.plan` may carry them as a note on each
increment, or as a last increment **Fill in the run notes**; items 3 and 4 belong to the release, so a last
increment of the plan fills items 1 and 2 and leaves items 3 and 4 to the release half. **The release half reads
these notes before it asks for the release approval.** This section is scanned with the rest of the spec.

1. **The plan.** Whether the planner accepted this spec and the context layer (`architecture.md`, `CLAUDE.md`) as
   written, or what it asked to change.
   — *to fill in*
2. **The build and the evidence rule.** Whether the build accepted the evidence rule for the `[guard]` criteria —
   a green run on the unchanged code at the selected commit and again after the change, never a RED run — or
   what it asked for instead.
   — *to fill in*
3. **The leak scan.** Whether the leak scan caught what the FCR's banner forbids: the probe's hit count, and each
   scanned file's count (the diff, the commit messages, each document under `docs/` and the context layer).
   — *to fill in*
4. **The platform checks.** How FCR §5 task 6's per-platform results, and any exception, were recorded (where,
   by which role, on which date).
   — *to fill in*

---

## Fork change request (verbatim)

# Fork change request: decode images concurrently instead of on one process-wide thread (3.8.x)

> **This document crosses to a public repository.** It never contains product source (copied, paraphrased or
> "adapted"), a product package, class, file, method or module name, an issue-tracker key, an internal hostname,
> or a credential. It does not link to, or name, the findings or assessment it came from. Before anyone sees it,
> `scan-leaks -Deny <list>` must report no hits. This banner names nothing private, so it may stay.

| Field | Value |
|---|---|
| **Weasis target** | **Recommended:** a new branch cut at the tag commit `389f93921da6b1d3658f545d64a64437d4af032f` (`v3.8.1`), subject to the check in §0.1. **Alternative:** branch `3.8.x`, head `412c16a593d60e1d9b57af06f5f35a772db0e593` (checked 2026-10-27), which is 23 commits ahead of the tag (§0). Every path and line cited in §1–§3 is the same on both. |
| **Public-safe sign-off** | senior software architect · 2026-10-29 (first signed 2026-10-27; re-confirmed 2026-10-28 for §0, §0.1, §5 and the review amendments of that day; re-confirmed 2026-10-29 for the §2 *Concurrency contract* table, the §5 task table and its stop rules, the restored upstream commit subjects, the removal path of a cancelled task in §2 item 1, and the thumbnail submitter's thread in the *Backpressure* row). |

## 0. Branch baseline: `3.8.x` against `v3.8.1`

This was checked on 2026-10-27, in a temporary clone of the fork.

**The branch only adds to the tag.**

- `v3.8.1` is a lightweight tag on `389f939`. The merge-base of `3.8.x` and `v3.8.1` is `389f939` itself.
- No commit of the tag is missing from the branch. The branch has 23 commits after the tag, all from upstream (2022-02-03 to 2022-07-27).

**The files this request changes are unaffected.**

- `ImageElement`, `MediaElement`, `ThreadUtil`, `Thumbnail`, `NativeCache`, `DicomImageElement` and `MediaReader` are
  byte-identical at the tag and at the head.
- `DicomMediaIO` has one change: whitespace inside a comment at `:366`. No line moves.

So every citation in §1–§3 holds on the branch head.

**The branch already declares the next version.** It sets `3.8.2-SNAPSHOT` (`weasis-parent/pom.xml`
`revision` / `changelist`) and moves `spotless-maven-plugin` from `2.12.3` to `2.23.0`.

**The 23 commits:**

| Commit | Date | Subject (upstream, verbatim) |
|---|---|---|
| `a9b12d04` | 2022-02-03 | Automatic changelog update |
| `f4ae3aa6` | 2022-02-08 | Next dev version |
| `c7e22b61` | 2022-02-08 | Merge branch 'master' into 3.8.x |
| `b36b970d` | 2022-03-21 | Update jpackage resources |
| `51968dde` | 2022-03-17 | Fix modality selection |
| `3b4df7de` | 2022-03-14 | Fix display result of QR when status is not success |
| `3475affd` | 2022-04-20 | Fix contrast issue when reopening the Editor Tool |
| `7ab9d318` | 2022-04-20 | Fix DicomWeb query with null query parameters |
| `b483d915` | 2022-05-23 | Cannot disable dicom export & dicom send in Weasis with properties #269 |
| `59b95b70` | 2022-05-20 | DICOM files in DICOMDIR file-set may have a filename extension. #272 |
| `8b61db45` | 2022-05-18 | Additional CD-ROM mount point for Linux. #271 |
| `a386cfdf` | 2022-05-27 | Patient and study duplication at DICOMDIR import. #276 |
| `3d101c28` | 2022-06-03 | Support of Pseudocolor Softcopy Presentation Stage Storage SOP Class #280 |
| `62a9f84d` | 2022-06-03 | Reset action do not render correctly the view with Presentation State #281 |
| `cef30fed` | 2022-06-08 | Pixel Info graphic is not updated in the table when drawing #282 |
| `c430b193` | 2022-06-08 | Importing an annotations xml file doesn't work #258 |
| `31404301` | 2022-06-08 | Some views of the MPR are shifted when changing the layout #262 |
| `65ce8f6e` | 2022-06-10 | Fix empty TextLayout |
| `fadf1954` | 2022-06-17 | Fix empty TextLayout |
| `de403668` | 2022-07-27 | Histogram issue when all values are outside the W/L #305 |
| `b762b4fd` | 2022-07-27 | The DICOM SR view always opens at the end of the page #313 |
| `62dc0e6f` | 2022-07-27 | Display of graphic information from a DICOM Comprehensive SR with a multiframe series #309 |
| `412c16a5` | 2022-07-27 | Reformat |

**Behaviour that a 3.8.x-based release carries beyond this request.** These are the changes a downstream consumer
of the branch should check:

1. **`b483d915` (#269).** `ConfigData.applyLocalCodebase()` no longer forces `weasis.export.dicom`,
   `weasis.export.dicom.send`, `weasis.import.dicom` and `weasis.import.dicom.qr` to `true` for a local codebase.
   Weasis's own readers default to `true` when the property is absent. Code that reads them with a default of `false`
   sees them switch off.
2. **`a386cfdf` (#276).** `DicomModel.mergePatientUID()` and `mergeStudyUID()` now fire `REMOVE` for the patient or
   study group being merged away. `DicomMediaUtils` now checks patient, study and series consistency before
   overwriting the group tags, and logs more warnings.
3. **`62a9f84d` (#281).**
   - The enum constant `ResetTools.ROTATION` is removed. That is binary-incompatible for code naming it, and it
     shrinks `ResetTools.values()`.
   - `EventManager.reset()` no longer resets rotation alone.
   - `View2d.reset()` re-applies a presentation state.
   - `ActionW.SHOW_HEADER` gets its own command id, `show_header`; before, it shared `reset` with `ActionW.RESET`.
4. **`31404301` (#262).** `MPRContainer.setLayoutModel()` resets the zoom after a layout change.
5. **`3d101c28` (#280).** `WindowAndPresetsOp.process()` converts pseudo-color presentation states. Otherwise it is
   identical to `WindowOp.process()`.
6. **Everything else** is UI and import fixes (DICOMDIR import, explorer layout, Q/R, SR view, acquire, histogram
   defaults, label painting, measure tool, group menu, the download-queue log condition), jpackage installer
   resources and formatting.

Applying this request to a branch cut at `389f939` instead would carry none of the changes above.

### 0.1 Recommended target, and the check before implementation

**Recommendation: apply this request on a new branch cut at the tag commit `389f939`, not on `3.8.x`.**

- **Why.** The downstream product runs 3.8.1 today. A branch cut at the tag carries exactly one change, this
  request, so the first internal release differs from what is deployed by that change alone.
- **What `3.8.x` would add.** Releasing from it would also bring the 23 commits of §0. Two of them (items 1 and 2
  above) change behaviour a downstream consumer may rely on, and would need their own verification first.
- **`3.8.x` stays as it is.** Moving it back to the tag would rewrite upstream history. Taking those commits can
  later be a separate, deliberate upgrade.

**Gate before any implementation work: confirm which 3.8.1 the product actually ships.** The recommendation assumes
the deployed 3.8.1 artifacts are the build of the tag. The expectation is that they are identical, but the branch
is cut only after this is shown.

**Why a checksum comparison cannot answer it.**

- The deployed Weasis modules are not upstream binaries. `nroduit/mvn-repo` hosts Weasis's dependencies (for example
  `weasis-core-img` 4.5.3 and `weasis-dicom-tools` 5.25.1), but not the 3.8.1 modules: `weasis-core-api` and
  `weasis-dicom-codec` return 404 there (checked 2026-10-27).
- The product builds the modules itself from a tag and deploys them to its own repository.
- Its jars are not reproducible builds: entry timestamps carry the build date, and the manifest records no SCM
  revision.

So the comparison is made on the compiled content.

1. **Inventory.** List every `org.weasis*` artifact at version `3.8.1` that the product resolves. Use the copies its
   own repository serves.
2. **Rebuild both candidates.** In a temporary clone, build the tag (`389f939`) and the `3.8.x` head. Use the JDK the
   deployed jars record (`Build-Jdk-Spec`) and the same target level (class-file major version).
3. **Compare the complete contents of each jar**, not only its classes:
   - **Entries.** The full entry list (every path, classes and resources) must equal the list of one build.
   - **Classes.** Diff `javap -v -p -c` against both builds, so attributes and constants count, not only method
     bodies. Constant-pool indices (`#n`) are stripped so ordering noise does not count.
   - **Resources.** Every other entry (message catalogues, configuration, images, service files) must be
     byte-identical.
   - **Build metadata.** `META-INF/MANIFEST.MF` and `META-INF/maven/**/pom.properties` are compared after
     normalizing their build metadata (dates, builder and JDK lines).
   - **Why resources matter.** A resource-only difference is a real difference. For example, `62a9f84d` changed a
     message key alongside its classes.
   - Jar entry timestamps are read as context only. A build date after the tag does not, on its own, say which
     commit was built.
4. **Markers.** These tell the two candidates apart quickly. Read them from the constant pool and the method bodies
   (`javap -v`, `javap -c -p`). A byte grep is not enough: for example, `show_header` also occurs inside the message
   key `ActionW.show_header` at the tag.
   - `ConfigData`: the four property names of §0 item 1 are present at the tag, absent at the head.
   - `ResetTools`: declares `ROTATION` at the tag.
   - `ActionW`: the `SHOW_HEADER` command id is `reset` at the tag; at the head, `show_header` is a constant of its
     own.
   - `DicomModel.mergePatientUID()` and `mergeStudyUID()`: fire only `ADD` at the tag; at the head they also fire
     `REMOVE`.
   - `DicomDirImport`: no `/run/media/` at the tag. It comes from `8b61db45`, which is dated 2022-05-18.
5. **Decide.** Every artifact must match **the same** commit; a branch is cut only at a commit the whole deployed
   set comes from.
   - **Every artifact matches the tag:** cut the new branch at `389f939`.
   - **Every artifact matches one and the same later commit** (the `3.8.x` head or a commit between): cut at that
     commit instead, and record which §0 commits are already deployed.
   - **The artifacts match different commits:** stop. A branch cut at any one of them would carry only part of
     the deployed set; the mixed build must be explained first.
   - **An artifact matches no commit:** stop. The deployed build's origin must be explained before the fork is
     changed.

The outcome of this check, and the commit the branch is cut from, are recorded here before work starts. The check
is task 1 of §5.

**Preliminary marker run (2026-10-27): every marker points to the tag.**

This read local copies of the deployed jars, not a full comparison:

- `ConfigData` still sets the four properties;
- `ResetTools` declares `ROTATION`;
- `SHOW_HEADER` loads `reset`, and there is no `show_header` constant;
- both `DicomModel` merge methods fire `ADD` only;
- `DicomDirImport` has no `/run/media/`.

The deployed jars were built on 2022-05-23. By then `3.8.x` already held `8b61db45` and `59b95b70`, and the
markers show neither. So the build was made from the tag, not from the branch as it stood that day.

This supports the recommendation, but it does not replace steps 1–3. Steps 1–3 still run on what the repository
serves, for every artifact.

**Outcome (2026-11-05, operator):** every artifact matches the tag; the branch is cut at 389f939. The private-side steps of task 1 are closed by this decision; the fork session takes only the outcome and the commit.

## 1. Current behaviour

All paths are at `v3.8.1`.

**Every pixel decode Weasis performs runs on one thread.** That is, every decode through `ImageElement.getImage()`
and through thumbnails. `MediaReader.getImageFragment()` has a single caller in Weasis, `ImageElement.loadImage()`
(`ImageElement.java:278`), which runs inside the executor below. A caller outside Weasis could still invoke
`getImageFragment()` on its own thread; this request is about the path Weasis provides.

- `ImageElement.IMAGE_LOADER` is `ThreadUtil.buildNewSingleThreadExecutor("Image Loader")`
  (`weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/ImageElement.java:41-42`;
  `weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/util/ThreadUtil.java:32-33`).
- `ImageElement.startImageLoading()` submits a `Load` task to it and blocks on `future.get()` (`ImageElement.java:409-417`).
- `getImage(OpManager, boolean)` is `synchronized` per element (`ImageElement.java:368`).
- `Thumbnail` submits its reads to the same executor
  (`weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/Thumbnail.java:316`).
- The decode itself is `ImageElement.loadImage()` → `MediaReader.getImageFragment()` (`ImageElement.java:277-278`).
  For DICOM that is `DicomMediaIO.getImageFragment()` → `getUncacheImage()` → the native `Imgcodecs.dicomRawFileRead`
  / `dicomJpgFileRead` / `dicomRawMatRead` calls
  (`weasis-dicom/weasis-dicom-codec/src/main/java/org/weasis/dicom/codec/DicomMediaIO.java:714-729`, `:812-916`).

The result: however many threads call `getImage()` on different elements, and however many cores the machine has,
decodes are served one at a time. For compressed series (JPEG 2000 Lossless in particular), decoding dominates
the cost of loading a volume. A caller that loads a whole series from several threads therefore gains nothing.

**Upstream already made this change on the 4.x line.**

- `v4.6.3` replaced the executor with `ThreadUtil.newManagedImageIOThreadPool("ImageLoader")`: a fixed pool of
  `max(2, min(min(16, maxMemoryMB / 100), 2 × cores))` threads, a bounded queue and `CallerRunsPolicy`.
- `v4.7.2` then added a guard that re-decodes when a concurrent cache eviction released the native buffer just
  returned (`isBufferReleased` in `ImageElement`).

**Two latent races in `DicomMediaIO` would surface once decodes overlap.**

1. **The lazy fragment index is not thread-safe.** `fragmentsPositions` (`DicomMediaIO.java:287`) is a plain
   `ArrayList`. `buildSegmentedImageInputStream()` fills it lazily, without synchronization, for multi-frame files
   with several fragments per frame (`:1118-1131`).
   - Two concurrent first decodes of frames of the same file can both fill it.
   - The size check then fails with `Cannot match all the fragments to all the frames!` (`:1133`, `:1148`).
2. **A reset can run in the middle of a decode.** The `readingImage` flag (`:305`) is never set to `true` at the
   tag, so it does not protect decoding.
   - `reset()` (`:1188-1199`) clears `tsuid` and `dis` (`:1201-1206`). It is reached through `close()` → `dispose()`
     (`:1043-1045`, `:1180-1185`), which the image-cache eviction callback calls (`ImageElement.java:51-55`).
   - A decode of another frame of the same reader reads those fields (`:817-818`, `:1077`, `:1119`).

   Today the window between an eviction on a caller thread and the single decode thread already exists.
   Concurrent decoding widens it.

## 2. Target behaviour / API contract

1. **`IMAGE_LOADER` becomes a bounded multi-thread pool.**
   - Its size comes from the available processors and the maximum heap, in the same spirit as upstream `v4.6.3`.
   - Threads are daemon threads named `Image Loader-<n>`.
   - Its backpressure never drops a task and **never runs a decode on the submitting thread**. Use a submission that
     blocks until there is capacity, or an unbounded queue like today's. Both submitters (`ImageElement.java:414`,
     `Thumbnail.java:316`) wait on `future.get()`, so the queue holds at most one live task per waiting caller. An
     interrupted caller cancels its task (`ImageElement.java:418-422`), but cancelling does not remove it from a
     `ThreadPoolExecutor` queue: it stays there, keeping its element reachable, until a worker discards it, and the
     element can be submitted again meanwhile. Today's single-thread queue already behaves this way. With a bounded
     queue those entries would also take capacity, so remove a task from the queue when its caller cancels it
     (`ThreadPoolExecutor.remove`). The pool is held in a private `ThreadPoolExecutor` reference, and the public
     `IMAGE_LOADER` is assigned from it, keeping its type `ExecutorService` (item 3). Removal goes through that
     private reference, with the `Future` that `submit` returned: it is the queued task itself, a `FutureTask`,
     and therefore a `Runnable`. **Not `CallerRunsPolicy`**: when the queue
     is full it runs the decode on the caller's thread, which breaks the rule in item 3. Upstream `v4.6.3` uses
     `CallerRunsPolicy`, so that part is not ported.
   - **A blocking submission waits interruptibly, and an interrupt during it is cleaned up.** Today the element is
     marked as loading before the task is submitted (`ImageElement.java:411-414`), and the mark is cleared only on
     the normal path after the wait (`:440`). An exception out of a submission that was interrupted while waiting
     for capacity would therefore leave the element marked as loading for good, and every later call would skip it.
     An interrupt while waiting for capacity must end like an interrupt during the wait for the result
     (criterion 7): the interrupt status is restored and the element's loading mark is cleared so a later call can
     decode it.
   - **What an interrupt leaves behind depends on where the task is.**
     - **Not yet queued, or still queued:** no task is left for that caller. A queued one is removed before the
       loading mark is cleared.
     - **Already taken by a worker:** only the caller's wait ends. The decode cannot be stopped (the native decoder
       does not observe interrupts); it finishes on its worker and its result is discarded, as today. Because the
       mark is cleared, a later call may start a second decode of the same element while the abandoned one is still
       running. On today's single thread the two run one after the other; on a pool they can overlap. That overlap
       is allowed (item 3) and safe (item 4). Later callers do **not** join the abandoned decode: that would need a
       per-element future, which this change does not introduce.
2. **A system property sets the pool size explicitly** (for example `weasis.image.loader.threads`). The value `1`
   restores today's single-thread behaviour exactly.
3. **Behaviour that must not change:**
   - the field `public static final ExecutorService IMAGE_LOADER` (name, type, visibility), and every public or
     protected signature in `ImageElement`, `MediaElement`, `Thumbnail` and `DicomMediaIO`;
   - decoding stays off the caller's thread, so an interrupted caller cancels its own wait, not a decode running in
     its stack;
   - per element, at most one decode is in flight (`setAsLoading` / `setAsLoaded`, `MediaElement.java:141-151`), and
     concurrent callers of the same element still receive the same cached image. The one exception is a decode
     abandoned by an interrupted caller, which may still be finishing while a later call decodes the element again
     (item 1);
   - the image-cache semantics (`NativeCache`, `ImageElement.java:44-61`), the out-of-memory handling
     (`ImageElement.java:371-378`, `:424-426`), and the decoded pixel values for every transfer syntax.
4. **`DicomMediaIO` becomes safe for concurrent decodes of the same reader:**
   - the fragment index is built once per reader and published atomically, for example built in a local list
     under the reader's lock and then assigned;
   - `reset()` does not clear the state a decode in progress reads. A plain `boolean` cannot express several
     concurrent decodes, so this needs an in-flight counter around the use of that state in
     `getImageFragment()`, or an equivalent;
   - a reset deferred that way is **not lost**. It runs exactly once, when the last in-flight decode ends, or
     `close()` waits for the count to reach zero. Either way the stream is closed and the cleared state stays
     cleared, as today.
5. **The upstream `v4.7.2` released-buffer guard is ported, if it applies on 3.8.x.** If `PlanarImage` on 3.8.x
   cannot tell that its native buffer was released, say so in the change and leave this point out.

### Concurrency contract

The change touches an executor and its queue, so every item is stated. It restates items 1–4 above; where they
differ, items 1–4 govern.

| Item | Contract |
|---|---|
| **Backpressure** | Work beyond the pool's capacity waits; it is never dropped and never run on the submitting thread. Two designs are allowed: a bounded queue whose submission blocks, interruptibly, until there is capacity; or an unbounded queue as today. Not `CallerRunsPolicy` (upstream `v4.6.3` uses it; that part is not ported). The submitting caller is the one who observes it, by waiting. The thumbnail submitter (`Thumbnail.java:316-319`) runs on the single "Thumbnail Loader" thread (`THUMB_LOADER`, `:57-58`, through a `SwingWorker` started at `:228-241`), never on the event-dispatch thread: under a blocking submission it is that thread that waits for capacity, which delays thumbnails only. |
| **Queue bound** | Bounded design: the queue's capacity. Unbounded design: in practice the callers waiting on `future.get()` (`ImageElement.java:414-417`, `Thumbnail.java:316-319`). In both, `Future.cancel` does **not** remove a queued task from a `ThreadPoolExecutor` queue, so an interrupted caller's task would otherwise stay queued, keep its element reachable and, with a bounded queue, take capacity. A task is therefore removed from the queue when its caller cancels it (`ThreadPoolExecutor.remove`, through the private typed reference of item 1, since the public field stays `ExecutorService`); once removed, cancelled tasks do not count toward the bound. |
| **Interruption and cancellation** | **Not yet submitted** (waiting for capacity): the wait ends, the interrupt status is restored, the element's loading mark is cleared, and no task is left. **Queued:** the task is removed from the queue before the loading mark is cleared, and the interrupt status is restored. **Running** (taken by a worker): only the caller's wait ends; the native decoder does not observe interrupts, so the decode finishes on its worker and its result is discarded, as today. A later call may then decode the same element again; that one overlap is allowed (item 3) and safe (item 4). Later callers do not join the abandoned decode. |
| **Invariants after an interrupt** | The call returns with the thread's interrupt status set. The element's loading mark is cleared, so a later call can decode it. No queued task is left for that element. Apart from the abandoned decode, at most one decode per element is in flight. The reader stays consistent under that overlap (item 4), and a reset deferred by an in-flight decode runs exactly once, after the last one ends. |
| **Thread confinement** | Decodes run only on the pool's daemon threads, named `Image Loader-<n>`, never on a submitter's thread. `getImage(OpManager, boolean)` stays `synchronized` per element (`ImageElement.java:368`). A pool size of `1` restores today's single thread exactly (item 2). The change adds no delivery to the event-dispatch thread: callers receive their result on their own thread, as today. |
| **Failing fixtures, per §3 criterion** | **1** fails on the unchanged code: with pool size ≥ 2, the second `getImageFragment()` never starts while the first blocks on the latch, because one thread serves both. **4** fails: concurrent first decodes of frames of one multi-frame, multi-fragment-per-frame reader fill the lazy fragment index together and raise `Cannot match all the fragments to all the frames!` (`DicomMediaIO.java:1118-1133`). **5** fails: `close()` during a decode clears `tsuid` and `dis` (`:1201-1206`) under the running decode, because `readingImage` is never set (`:305`). **2, 3, 7 and 8 pass on the unchanged code by design**: they pin behaviour the change must keep (one thread at size 1; one decode per element with eight callers held inside it by a start barrier; interrupt status and later successful decode with the latch released only after the interrupted call returned; no decode on a caller's thread). **6** decodes through distinct elements and readers; on the unchanged code the single thread serializes it, so it checks the native codec only once the pool exists. **9** cannot be set up on the unchanged code, whose submission never blocks; it is written with a bounded queue, if one is chosen. |

## 3. Acceptance criteria (Weasis tests)

Criteria 1–3 and 7–9 use a fake `MediaReader` whose `getImageFragment()` blocks on a shared latch and then returns
a small non-null image; they need no native library. Criteria 4–6 use DICOM fixtures and the native codec.

1. **Given** a pool size of 2 or more and two `ImageElement`s backed by the blocking fake reader, **when**
   `getImage()` is called on each from its own thread, **then** both `getImageFragment()` calls are in progress at
   the same time before either returns.
2. **Given** the pool-size property set to `1`, **when** the scenario of criterion 1 runs, **then** the two
   `getImageFragment()` calls never overlap.
3. **Given** one `ImageElement`, the fake reader returning one and the same non-null image instance, and eight
   threads that pass a shared start barrier immediately before calling `getImage()` on it, **when** the latch is
   released only after the barrier has opened and the first `getImageFragment()` call is in progress, and they all
   return, **then** `getImageFragment()` ran exactly once and every caller received that image instance.
4. **Given** a fresh multi-frame JPEG 2000 file and a fresh multi-frame JPEG file, each with several fragments per
   frame, **when** all frames are decoded concurrently from eight threads, **then**:
   - every frame decodes, with pixels equal to a serial decode;
   - no `Cannot match all the fragments to all the frames!` is raised.

   **All frames of one repetition decode through the same `DicomMediaIO` instance**, because the race lives in that
   reader's lazy fragment index. Each repetition starts from a new reader. Repeat at least 50 times.
5. **Given** a multi-frame reader with one frame being decoded, **when** `close()` is called on the same reader
   from another thread during that decode, **then**:
   - the decode completes with the same pixels as an undisturbed decode;
   - once it has ended, the reset has run: the reader's stream is closed and its parse state is cleared, exactly
     as after a `close()` with no decode in flight.
6. **Given** single-frame fixtures in each supported syntax, **when** they are decoded concurrently from eight
   threads, 20 rounds each, **each thread through its own `ImageElement` and reader for the file** (so the
   per-element guard that criterion 3 pins cannot serialize them), **then** every result is pixel-identical to a
   separate serial decode of the same file. The
   syntaxes are: explicit VR little endian, deflated, RLE lossless, JPEG baseline, JPEG lossless, JPEG-LS lossless
   and near-lossless, and JPEG 2000 lossless and lossy. This criterion checks that the bundled native codec is
   thread-safe at `weasis.opencv.native.version` `4.5.3-dcm`.
7. **Given** a caller blocked in `getImage()` on the fake reader, **when** that caller thread is interrupted,
   **then**:
   - the call returns with the thread's interrupt status set, while the latch is still closed;
   - after that, the test releases the latch, and a later `getImage()` on the same element from another thread
     decodes it successfully. With a pool size of 2 or more, its `getImageFragment()` may overlap the abandoned one
     (§2 item 1); the test must not fail on that overlap.
8. **Given** the pool size set to 1 and many more callers than pool threads (with a bounded queue, also more than
   the queue can hold), each on its own element backed by the blocking fake reader, **when** they all call
   `getImage()`, **then** every `getImageFragment()` runs on a pool thread, never on a caller's thread. The fake
   reader records `Thread.currentThread()`.
9. **Only if the queue is bounded.** **Given** the pool size set to 1, its worker held by the blocking fake reader
   and the queue full, **when** one more caller calls `getImage()` on its own element and is interrupted while its
   submission waits for capacity, **then**:
   - the call returns with the thread's interrupt status set;
   - no task for that element is left in the queue;
   - once the latch is released, a later `getImage()` on the same element from another thread decodes it
     successfully.

## 4. How the product will use the change

A downstream viewer builds GPU volumes from DICOM series that have finished loading. Typical series are CT with
200–900 single-frame JPEG 2000 Lossless instances.

- **Per instance,** its loader calls `DicomImageElement.getImage()`, `getMinValue()`, `getMaxValue()` and
  `getModalityLookup()` once, then converts the pixels and uploads them.
- **Today** these calls come from one worker thread per volume, while the viewer's per-instance loaders run during
  download, and several volumes can be built at once. All of their decodes queue on the single `IMAGE_LOADER`
  thread.
- **The viewer plans to issue the per-instance calls of one volume from several worker threads.** It relies on this
  change for those calls to decode in parallel.
- **It does not depend on decode order.** It reads `IMAGE_LOADER` only indirectly, through `getImage()`.

## 5. Implementation tasks

These are listed in order; each task starts only when the one before it has a recorded result, and the tasks are
not reordered. Task 1 is a gate: if it does not pass, nothing else starts.

| # | Task | Gate / stop rule |
|---|---|---|
| 1 | **Baseline check (gate).** Run the check in §0.1, steps 1–5, against the 3.8.1 artifacts the downstream product deploys. Record the outcome and the commit it selects in §0.1. The steps that are specific to the product's own repository stay with the requester; this request needs only the outcome and the commit. | **Every artifact matches the tag:** continue with task 2 at `389f939`. **Every artifact matches one and the same later commit:** continue at that commit, and list which §0 changes the release will then carry. **Stop** if the artifacts match different commits, or an artifact matches no commit: the deployed build is explained before any change; the requester decides. |
| 2 | **Cut the branch** at the commit task 1 selected. Do not move or rewrite `3.8.x`. | — |
| 3 | **Write the acceptance tests of §3 first**, and run them on the unchanged code before implementing. | **Stop** if criterion 1, 4 or 5 passes there (it detects nothing; its fixture of §2 *Concurrency contract* is wrong, not the code), or if criterion 2, 3, 7 or 8 fails there (it pins behaviour the change must keep). Criterion 6 checks the native codec only once the pool exists. Criterion 9 cannot be set up on the unchanged code, whose submission never blocks; write it together with a bounded queue, if one is chosen. |
| 4 | **Implement §2:** the multi-thread executor and its size property; the `DicomMediaIO` fragment index and the reset guard; the released-buffer guard, if `PlanarImage` on this line can detect a released buffer. | — |
| 5 | **Set the version** of the internal release across the Weasis family: the parent's `revision` (`weasis-parent/pom.xml:17` at `v3.8.1`), which every module inherits. Upstream never shipped a `3.8.2`, so an internal `3.8.2` cannot be confused with an upstream release. | — |
| 6 | **Native thread-safety (criterion 6).** Run it on every platform the product ships with the bundled native library. The pre-check below covers part of it. | **Stop** if any syntax decodes differently under concurrency: the request cannot proceed as written, and the finding is reported back. |
| 7 | **Leak scan and release.** Scan the diff and the commit messages for anything that is not Weasis's own (the rule in this document's banner). Then build and publish the internal version. Pinning it on the consumer's side, and measuring the gain there, are not part of this request. | **Stop** on any scan hit: reword and rescan before anything is published. |

**Task 6 pre-check already done (2026-10-28), Windows x86-64 only.** It called `Imgcodecs.dicomJpgFileRead` directly,
outside Weasis.
   - **Correctness:** eight single-frame fixtures (monochrome and YBR_RCT JPEG 2000, one of them 4240×3480, plus two
     JPEG Lossless) were decoded 1,280 times from 8 threads. Every result was pixel-identical to the single-thread
     reference, with no failures.
   - **Scaling:** a 400-instance 512×559 JPEG 2000 Lossless series scaled near-linearly, from 37.8 instances/s on
     one thread to 351/s on 12 (6 hyper-threaded performance cores). The decoder shows no global lock.
   - **Still owed by this task:** the other platforms, and every criterion 6 syntax the pre-check did not cover:
     explicit VR little endian, deflated, RLE lossless, JPEG baseline, JPEG-LS lossless and near-lossless, and
     JPEG 2000 lossy. The first two do not go through `dicomJpgFileRead` at all: explicit VR little endian is read by
     `Imgcodecs.dicomRawFileRead` and deflated by `Imgcodecs.dicomRawMatRead` (`DicomMediaIO.java:898-908`), so the
     pre-check exercised neither native entry point.
   - **Measure in a foreground session.** On Windows a windowless background process is power-throttled and kept on
     efficiency cores, which made the same decoder look 3–4× slower and capped its scaling at about 3.4 cores.
