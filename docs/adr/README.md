# Architecture decision records

One decision area per document. Each ADR states the decision **as it
stands today** — corrections and withdrawn arguments are resolved, not
narrated. The argument that produced a decision lives on the tracker;
the ADR links it under *Source*.

Every ADR carries the same seven sections, in this order:

**Status** · **Context** · **Decision** · **Consequences** · **Traps** ·
**Rejected** · **Source**

An ADR that carries unresolved fog adds an **Open** heading before
*Rejected*, naming each open question and what would unblock it. Fog is
recorded, never omitted — an absent Open heading means the area has
none, not that nobody looked.

*Decision* is written to be read by a first-year programmer without
*Traps*. *Traps* is the section that earns the document: the failure
modes that compile clean and fail on the field, each with the source
location that verifies it. *Rejected* records what was ruled out and
why, including where an option must not be re-raised without new
evidence.

## Claim tags

Every factual claim carries how it was checked. An ADR's Status section
names the source tree and commit its `[source]` tags were read at.

| Tag | Means |
|---|---|
| **[source]** | Read out of a named file, cited by path and line. |
| **[measured]** | Measured on the Pi. The number and its method live in `docs/research/`. |
| **[executed]** | Verified by running it, not by reading it. |
| **[field]** | From field evidence — someone else's season, not ours. |
| **[decided]** | A team decision. There is nothing to verify: it is a choice, and the ticket carries the argument. |
| **[unverified]** | Nobody has checked. Tagged so that fog stays visibly fog rather than reading as fact. |

The first four are not interchangeable, and the distinction is the point
— a number measured on the Pi and a behaviour inferred from source are
worth different amounts when one of them turns out to be wrong.

## Index

| ADR | Area | Status |
|---|---|---|
| [0001](0001-opmoderobot-and-commands-v3.md) | `OpModeRobot` and Commands v3 | Not yet written |
| [0002](0002-loop-rate-and-jvm.md) | 200 Hz loop, no JVM tuning, no loop-count assumptions | Not yet written |
| [0003](0003-project-and-package-structure.md) | Project and package structure | **Accepted** — 2026-08-26 |
| [0004](0004-config-as-code.md) | Config-as-code | Not yet written |
| [0005](0005-telemetry-and-log-schema.md) | Telemetry and log schema | Not yet written |
| [0006](0006-commands-v3-house-style.md) | Commands v3 house style | Not yet written |
| [0007](0007-can-topology-and-frames.md) | CAN bus topology and frame allocation | Not yet written |
| [0008](0008-closed-loop-on-the-spark.md) | Closed loop on the SPARK | Not yet written |
| [0009](0009-characterisation-and-tuning.md) | Characterisation and tuning | Not yet written |
| [0010](0010-simulation-architecture.md) | Simulation architecture | Not yet written |
| [0011](0011-autonomous-and-choreo.md) | Autonomous and Choreo integration | Not yet written |
| [0012](0012-pose-estimation-and-vision.md) | Pose estimation and the vision seam | Not yet written |
| [0013](0013-ci-and-test-strategy.md) | CI and test strategy | Not yet written |
| [0014](0014-ai-log-analysis-contract.md) | AI log-analysis contract | Not yet written |

Numbering follows decision area, not the date a document landed. Links
to unwritten ADRs are dead until they land; the row is here so the
shape of the set is visible from the start.

## Related documents

- [`CONTEXT.md`](../../CONTEXT.md) — the project glossary. Not yet written.
- [`VERSIONS.md`](../../VERSIONS.md) — the OS image ↔ allwpilib commit ↔ MRC API triple, vendordep versions, and every departure from the stock template. Not yet written.
- [`docs/research/`](../research/) — the measurements and source readings the ADRs cite, with their methods.
