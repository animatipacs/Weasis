# Instructions for agents working in this fork

1. **Weasis terms only.** Everything written here — code, tests, commit messages, documents — names Weasis
   types, modules and paths. Nothing names a downstream consumer, its code, its tracker, its hosts or its people.
2. **The change spec is the contract.** A change is built from `docs/specs/<slug>.md` and its plan
   `docs/plans/<slug>.md`; the spec's acceptance criteria are the tests, in the spec's order, and the modules it
   cites are the only ones that change. For `docs/specs/concurrent-image-decoding.md` those are
   `weasis-core-api` and `weasis-dicom-codec` (see `architecture.md`).
3. **The task order is fixed.** The plan follows the order of the implementation tasks of the fork change request
   quoted in the spec; a task starts only when the one before it has a recorded result, and no task is reordered
   or merged across a gate.
4. **Tests first.** A `[new behaviour]` criterion fails on the unchanged code before it passes; a `[guard]`
   criterion passes on the unchanged code and again after the change — its evidence is the green run, not a red
   one.
5. **The release branch is untouched.** Work on `change/<slug>`; never rebase, merge into or rewrite `3.8.x`.
6. **The version is set at release time** in the parent's `revision`, by the release step, once. A change
   branch edits no POM version.
7. **Every push is scanned** by the operator with a tool kept outside this repository; a scan hit stops the push
   until the text is reworded.

## Local Quality Gates

Run from the repository root (verified on 2026-11-05, Maven 3.9.9, JDK 17):

1. `mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am -DskipTests package`
2. `mvn -B -pl weasis-core/weasis-core-api,weasis-dicom/weasis-dicom-codec -am test`
