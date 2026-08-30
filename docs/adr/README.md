# Architecture decision records

One decision area per document. Each ADR states the current decision.
The tracker holds the discussion that led to it; the ADR links that
discussion under *Source*.

Every ADR carries the same seven sections, in this order:

**Status** · **Context** · **Decision** · **Consequences** · **Traps** ·
**Rejected** · **Source**

An ADR with unresolved fog adds **Open** before *Rejected*. It names each
question and what would answer it. No *Open* heading means no known open
questions.

*Decision* should make sense to a first-year programmer without *Traps*.
*Traps* lists failures that compile but fail on the field, with evidence.
*Rejected* lists ruled-out options and why.

## Claim tags

Every factual claim carries how it was checked. An ADR's Status section
names the source tree and commit its `[source]` tags were read at.

| Tag | Means |
|---|---|
| **[source]** | Read from a named file, cited by path and line. |
| **[measured]** | Measured on the Pi; method is in `docs/research/`. |
| **[executed]** | Verified by running it. |
| **[field]** | Evidence from another team's season. |
| **[decided]** | Team choice; the ticket records why. |
| **[unverified]** | Not checked yet. |

These tags are not interchangeable: source, measurement, and execution
support claims differently.

## Index

| ADR | Area | Status |
|---|---|---|
| [0001](0001-opmoderobot-and-commands-v3.md) | `OpModeRobot` and Commands v3 | **Accepted** — 2026-08-26 |
| [0002](0002-loop-rate-and-jvm.md) | 200 Hz loop, no JVM tuning, no loop-count assumptions | **Accepted** — 2026-08-26 |
| [0003](0003-project-and-package-structure.md) | Project and package structure | **Accepted** — 2026-08-26 |
| [0004](0004-config-as-code.md) | Config-as-code | **Accepted** — 2026-08-26 |
| [0005](0005-telemetry-and-log-schema.md) | Telemetry and the log | **Accepted** — 2026-08-28 |
| [0006](0006-commands-v3-house-style.md) | Commands v3 house style | **Accepted** — 2026-08-28 |
| [0007](0007-can-topology-and-frames.md) | CAN bus topology and frame allocation | **Accepted** — 2026-08-26 |
| [0008](0008-closed-loop-on-the-spark.md) | Closed loop on the SPARK | **Accepted** — 2026-08-26 |
| [0009](0009-characterisation-and-tuning.md) | Characterisation and tuning | **Accepted** — 2026-08-26 |
| [0010](0010-simulation-architecture.md) | Simulation architecture | **Accepted** — 2026-08-26 |
| [0011](0011-autonomous-and-choreo.md) | Autonomous and Choreo integration | **Accepted** — 2026-08-26 |
| [0012](0012-pose-estimation-and-vision.md) | Pose estimation and the vision seam | **Accepted** — 2026-08-26 |
| [0013](0013-ci-and-test-strategy.md) | CI and test strategy | **Accepted** — 2026-08-27 |
| [0014](0014-ai-log-analysis-contract.md) | AI log-analysis contract | **Accepted** — 2026-08-27 |
| [0015](0015-binding-revlibs-native.md) | Binding REVLib's native | **Accepted** — 2026-08-30 |

Numbers follow decision area, not document date. Every row has a document.

## Related documents

- [`CONTEXT.md`](../../CONTEXT.md) — the project glossary.
- [`docs/commands-v3-house-style.md`](../commands-v3-house-style.md) — the
  student guide for ADR 0006.
- [`docs/research/`](../research/) — cited measurements and source notes.
