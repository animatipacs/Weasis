# Architecture notes for this fork

This fork of Weasis keeps upstream's release branch `3.8.x` and the upstream tag `v3.8.1` (commit
`389f93921da6b1d3658f545d64a64437d4af032f`), from which `3.8.x` descends. Each internal change lives on a branch
`change/<slug>` cut from the commit its change spec names, is built by the planning and build skills from
`docs/specs/<slug>.md` and `docs/plans/<slug>.md`, and is released as the next internal version set in the
parent's `revision`, tagged `v<version>` on the change branch's head. The release branch is never rewritten.

## Modules a change may touch

The current change (`docs/specs/concurrent-image-decoding.md`) cites these modules only.

| Module | `pom.xml` | What it holds | Tests |
|---|---|---|---|
| `weasis-core-api` | `weasis-core/weasis-core-api/pom.xml` | The core API bundle: media elements and their loading and caching (`ImageElement`, `MediaElement`, `Thumbnail`, `NativeCache`), the `MediaReader` interface, `ThreadUtil` | `weasis-core/weasis-core-api/src/test/java` — JUnit 4 and AssertJ; 2 classes, 19 tests at `389f939` |
| `weasis-dicom-codec` | `weasis-dicom/weasis-dicom-codec/pom.xml` | DICOM parsing and pixel decoding: `DicomMediaIO` (a `MediaReader`) and `DicomImageElement`, decoding through the native codec | `weasis-dicom/weasis-dicom-codec/src/test/java` — JUnit 4, AssertJ, PowerMock with Mockito 2; 2 classes, 9 tests at `389f939` |
| `weasis-parent` | `weasis-parent/pom.xml` | The family's version (`revision`, `changelist`), properties, plugin and dependency management | — (changed only by the release step, for the version) |

## How to build and test

All commands run from the repository root. Verified on 2026-11-05 at `389f939` with Maven 3.9.9 and JDK 17;
the parent requires JDK 11 or later (`weasis-parent/pom.xml:21-22`) and compiles for Java 1.8 (`:20`).

- Build the cited modules and what they need:
  `mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am -DskipTests package` — exit 0.
- Run the cited modules' tests:
  `mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am test` — exit 0; `weasis-core-api`
  19 tests, `weasis-dicom-codec` 9 tests, no failures.
- Run one test class:
  `mvn -B -pl weasis-dicom/weasis-dicom-codec -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`
  — exit 0 (checked with `TagDTest`, 6 tests). For a class in `weasis-core-api`, use `-pl weasis-core/weasis-core-api`.
- Native libraries: the OpenCV-based DICOM codec (`opencv_java`) is copied into one bundle per platform by
  `weasis-opencv/weasis-opencv-core-<platform>/pom.xml`, seven platforms (`weasis-opencv/pom.xml:19-25`: Windows
  x86 and x86-64, Linux x86, x86-64, armv7a and aarch64, macOS x86-64), at `weasis.opencv.native.version`
  `4.5.3-dcm` (`weasis-parent/pom.xml:51`). The cited modules' tests do not load it at `389f939`. The machine
  these commands were verified on is Windows x86-64.
- `PlanarImage` (`org.weasis.opencv.data`) comes from `weasis-core-img` `4.5.3` (`weasis-parent/pom.xml:50`), a
  dependency outside this repository; `NativeCache` is in `weasis-core-api`.

## Facts a change relies on (at `389f939`, tag `v3.8.1`)

Paths: `ImageElement.java`, `MediaElement.java` and `Thumbnail.java` are under
`weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/media/data/`; `ThreadUtil.java` under
`weasis-core/weasis-core-api/src/main/java/org/weasis/core/api/util/`; `DicomMediaIO.java` under
`weasis-dicom/weasis-dicom-codec/src/main/java/org/weasis/dicom/codec/`.

- Every pixel decode runs on one thread: `ImageElement.IMAGE_LOADER` is
  `ThreadUtil.buildNewSingleThreadExecutor("Image Loader")` (`ImageElement.java:41-42`, `ThreadUtil.java:32-33`).
- `ImageElement.startImageLoading()` marks the element as loading, submits a `Load` task and blocks on
  `future.get()`; an interrupt re-asserts the interrupt status and cancels the future; the loading mark is
  cleared after the wait (`ImageElement.java:409-443`). `getImage(OpManager, boolean)` is `synchronized` per
  element (`:368`), with the out-of-memory retry at `:371-378`.
- The loading mark is `MediaElement.setAsLoading()` / `setAsLoaded()` (`MediaElement.java:141-151`).
- The image cache is a `NativeCache` of half the maximum heap; its eviction callback closes the element's reader
  (`ImageElement.java:44-61`).
- The decode is `ImageElement.loadImage()` → `MediaReader.getImageFragment()` (`ImageElement.java:277-278`), its
  only caller in this repository; for DICOM, `DicomMediaIO.getImageFragment()` → `getUncacheImage()` → the
  native `Imgcodecs.dicomRawFileRead` / `dicomRawMatRead` / `dicomJpgFileRead` (`DicomMediaIO.java:714-729`,
  `:812-916`).
- `Thumbnail` submits its reads to the same `IMAGE_LOADER` (`Thumbnail.java:316-319`) from the single
  "Thumbnail Loader" thread (`THUMB_LOADER`, `:57-58`, through a `SwingWorker` started at `:228-241`).
- `DicomMediaIO.fragmentsPositions` is a plain `ArrayList` (`:287`) filled lazily without synchronization
  (`:1118-1131`); a size mismatch throws `Cannot match all the fragments to all the frames!` (`:1133`, `:1148`).
- `DicomMediaIO.readingImage` (`:305`) is never set to `true`; `close()` → `dispose()` → `reset()` →
  `resetInternalState()` clears `iis`, `dis` and `tsuid` (`:1043-1045`, `:1180-1206`), which a decode reads
  (`:817-818`, `:1077`, `:1119`).

## Branch and version rule

- The release branch `3.8.x` declares `3.8.2-SNAPSHOT` (`weasis-parent/pom.xml:17-18` on `3.8.x`) and is never
  moved, rebased, merged into or rewritten.
- A change branch is cut from the commit its change spec names; `change/concurrent-image-decoding` is cut from
  `389f939` and declares `3.8.1` (`weasis-parent/pom.xml:17`, empty `changelist` at `:18`). A change branch does
  not change it.
- The release sets the next internal version once, in the parent's `revision` with an empty `changelist`,
  inherited by every module of the family; no module POM is edited for it.
- The fork's POMs carry no deploy target and get none.
