<!--
SYNC IMPACT REPORT
==================
Version change: [TEMPLATE] → 1.0.0 (initial ratification)
Modified principles: None (new document)
Added sections:
  - Core Principles (5 principles)
  - Performance Standards
  - Development Workflow
  - Governance
Removed sections: N/A (first version)
Templates updated:
  - .specify/templates/plan-template.md  ✅ compatible (Constitution Check section present)
  - .specify/templates/spec-template.md  ✅ compatible
  - .specify/templates/tasks-template.md ✅ compatible
  - .specify/templates/commands/         ✅ N/A (no commands dir)
Follow-up TODOs: None — all placeholders resolved.
-->

# POC XA Transaction Constitution

## Core Principles

### I. XA Transaction Integrity (NON-NEGOTIABLE)

All message production and database persistence MUST be performed within a single distributed
XA transaction spanning MySQL and Apache Artemis. The invariant is strict: if the transaction
rolls back for any reason — including an application exception thrown after the MQ message has
been sent but before the DB commit — the consumer MUST NOT receive the message.

**Non-negotiable rules:**
- Producers MUST enlist both the MySQL `DataSource` and the Artemis `ConnectionFactory` in the
  same JTA/XA transaction coordinator.
- No `@Transactional` usage without an XA-capable transaction manager (e.g., Atomikos, Narayana,
  or Spring Boot's `JtaTransactionManager`).
- Consumer receipt of a message from a rolled-back producer transaction is a critical test
  failure, not a flaky test.

### II. Failure-Scenario Coverage (NON-NEGOTIABLE)

Every failure path relevant to XA correctness MUST have at least one explicit automated test.
Tests MUST inject faults deterministically (not randomly in CI) and assert on the observable
outcome at the consumer side.

**Non-negotiable rules:**
- The canonical fault scenario — exception after MQ send, before DB commit — MUST be covered.
- Tests MUST assert that the consumer receives zero messages when the producer transaction rolls
  back.
- Fault injection MUST be controllable via a feature flag or test profile; random injection is
  only acceptable in manual exploratory runs.

### III. Performance Observability (MANDATORY)

Throughput (messages/second) and latency (p50, p95, p99 in milliseconds) MUST be measured and
reported for each test scenario. No feature is complete without a benchmark result attached to
its acceptance criteria.

**Non-negotiable rules:**
- Benchmarks MUST run against the real stack (MySQL + Artemis); no in-memory substitutes for
  performance tests.
- Results MUST be captured as structured output (JSON or CSV) so they can be compared across
  runs.
- Performance regressions >20% from baseline MUST be investigated before merging.

### IV. Technology Stack Constraints

The stack is fixed for this POC. Deviations require a constitutional amendment.

**Mandated stack:**
- **Runtime**: Java 25
- **Framework**: Spring Boot 3.5
- **Database**: MySQL (XA-capable driver, e.g., `mysql-connector-j` with XA support)
- **Broker**: Apache Artemis (XA-capable JMS provider)
- **Transaction Manager**: JTA-compliant (Atomikos or Narayana recommended)

Substituting any of the above (e.g., H2 for MySQL, ActiveMQ for Artemis) is prohibited in
POC validation scenarios; it MUST only happen in isolated unit tests with explicit justification.

### V. POC Scope Discipline (SIMPLICITY)

This is a proof-of-concept. Implementations MUST remain minimal and focused on validating XA
transaction semantics and measuring performance. Production-grade concerns are out of scope
unless explicitly added by constitutional amendment.

**Out of scope (unless amended):**
- Authentication and authorization
- Multi-tenancy or complex domain schemas
- High-availability / clustering of Artemis or MySQL
- UI or REST API layers beyond what is needed for test driving

## Performance Standards

| Metric | Target | Measurement Method |
|--------|--------|--------------------|
| Throughput | ≥ 500 committed msgs/sec (single producer) | JMH or custom benchmark |
| Commit latency p95 | ≤ 200ms end-to-end (producer DB + MQ) | Micrometer / custom timer |
| Rollback detection | Consumer receives 0 msgs within 5s of rollback | Consumer assertion in test |

These targets are starting baselines for the POC. They MUST be revisited and updated if
the hardware/environment changes materially.

## Development Workflow

- Every feature branch MUST reference a spec in `specs/`.
- Tests for failure scenarios MUST be written and confirmed failing before implementation
  of the corresponding fault-handling code.
- Commit after each completed task or logical group. Do not batch unrelated changes.
- All performance benchmark results MUST be committed alongside the feature that introduces
  or changes the measured behaviour.
- Use `./mvnw` (Maven Wrapper) or `./gradlew` (Gradle Wrapper) — never rely on a globally
  installed build tool version.

## Governance

This constitution supersedes all other practices documented in the repository. Amendments
require:

1. A written rationale explaining why the change is necessary.
2. Version bump following semantic versioning (see below).
3. An updated Sync Impact Report prepended to this file.

**Versioning policy:**
- MAJOR: Removal or incompatible redefinition of a Core Principle.
- MINOR: New principle, section, or materially expanded guidance.
- PATCH: Clarifications, wording fixes, non-semantic refinements.

All plan reviews (`/speckit-plan`) and task generation runs (`/speckit-tasks`) MUST include
a Constitution Check gate verifying compliance with Principles I–V above.

**Version**: 1.0.0 | **Ratified**: 2026-04-28 | **Last Amended**: 2026-04-28
