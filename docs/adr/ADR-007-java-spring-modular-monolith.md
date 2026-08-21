# ADR-007: Java 21 + Spring modular monolith

Status: Accepted (Phase 0); amended at Phase 1 start (build tool)

## Amendment (Phase 1)

Build tool is **Maven** rather than Gradle: the development environment ships Maven 3.9 and no
Gradle installation; wrapper bootstrapping would add moving parts without changing any
architectural property. Module boundaries, role-based process assembly, and extraction rules
are unchanged. The boundary-enforcement mechanism becomes Maven module scoping + review
(archunit-style enforcement revisited when the module graph grows).


## Context

§7 prefers Java 21 + Spring Boot. §64 forbids premature microservices while demanding clean
boundaries that permit later extraction.

## Problem

How do we structure the codebase so day-one simplicity doesn't become day-500 archaeology?

## Options considered

1. **Modular monolith (Gradle multi-module, roles via config) — chosen.**
2. **Microservices from the start** (api/scheduler/dispatcher/worker as separate deployables).
3. **Single-module monolith** ("split later, YOLO imports").

## Decision

One repository, multiple Gradle modules mirroring component boundaries
(`:api`, `:job-service`, `:scheduler`, `:dispatcher`, `:queue`, `:coordination`,
`:worker-runtime`, `:workflow-engine`, `:persistence`, `:common`). A single bootable app
assembles selected modules into a process; `--roles=api,scheduler,...` decides which run.
Cross-module access only through published interfaces; enforced by module dependency rules.

## Why this is best

- Distributed systems correctness is hard enough without network partitions *inside* our own
  codebase during development. In-process calls let Phase 1–2 focus on state machines and
  recovery, not service meshes.
- Module boundaries give us §64's real goal — good boundaries — with extraction remaining a
  packaging decision, not a rewrite.
- One deployable in compose can still run as 1 API + 3 schedulers + 5 workers (§71) by
  launching the same jar with different roles: distributed behavior is tested for real.

## Why alternatives were rejected

- **Microservices immediately:** adds serialization versions, network failure modes,
  deployment orchestration before any of it is needed; violates §64/§101 directly. The
  system becomes genuinely distributed when components genuinely need independent scaling
  (Phase 4+ triggers documented).
- **Single module:** boundaries would exist only as comments; six months later the scheduler
  imports worker internals and extraction is dead. The cost of Gradle modules is trivial;
  the insurance is not.

## Trade-offs accepted

- Shared JVM failure domain until split (documented; acceptable pre-Phase 4).
- Slight build-complexity overhead up front (worth it).
- Virtual threads (Java 21) used for per-job handler concurrency — simple blocking model
  with cheap threads; reactive complexity explicitly avoided.

## Consequences

Dependency-rule enforcement in build; interfaces at boundaries double as the future service
contracts; role-based process assembly tested in integration from Phase 3 onward.

## Revisit triggers

Divergent scaling/ownership/failure-isolation needs per component (the §64 criteria) — each
extraction gets its own ADR with the measurement that motivated it.
