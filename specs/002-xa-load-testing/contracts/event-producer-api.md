# Contract: Event Producer API

**Version**: 1.0.0
**Feature**: [spec.md](../spec.md)
**Plan**: [plan.md](../plan.md)
**Date**: 2026-04-29

## Overview

A single HTTP endpoint that accepts a produce-event request, processes it within a single XA transaction (MySQL write + JMS send, two-phase commit), and returns the persisted event.

This endpoint is the sole integration point between the Gatling load driver and the Spring Boot XA producer.

---

## Endpoint

### POST /api/events

Produces a new event within an XA transaction. The response is returned only after the XA transaction has fully committed on both MySQL and Artemis.

#### Request

| Field | Value |
|-------|-------|
| Method | `POST` |
| Path | `/api/events` |
| Content-Type | `application/json` |
| Authentication | None (POC scope) |

**Request body**:
```json
{
  "payload": "<string>"
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `payload` | string | yes | Non-empty; used as the event message body |

**Example**:
```json
{
  "payload": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### Responses

**201 Created** — XA transaction committed successfully:
```json
{
  "id": "<uuid>",
  "payload": "<string>"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string (UUID) | Unique identifier assigned by the system |
| `payload` | string | Echo of the request payload |

**500 Internal Server Error** — XA transaction failed (prepare or commit phase):
```json
{
  "timestamp": "<ISO-8601>",
  "status": 500,
  "error": "Internal Server Error",
  "path": "/api/events"
}
```

Standard Spring Boot error response; no custom body.

---

## Behaviour Guarantees

1. **Atomicity**: Either both the MySQL row and the Artemis message are committed, or neither is. A `201` response guarantees both are durable.
2. **No partial success**: A `500` response means the XA transaction was rolled back on both resources.
3. **Idempotency**: Not guaranteed. Each request produces a new row with a new UUID regardless of payload content.
4. **Ordering**: No ordering guarantee between concurrent requests.
5. **Latency**: Includes full XA 2PC overhead (≥4 network round-trips: prepare+commit on MySQL and Artemis).

---

## Gatling Integration

The Gatling simulation sends this request with a unique UUID payload per virtual user:

```java
http("POST /api/events")
    .post("/api/events")
    .header("Content-Type", "application/json")
    .body(StringBody(session -> "{\"payload\": \"" + UUID.randomUUID() + "\"}"))
    .check(status().is(201))
```

The `status().is(201)` check counts any non-201 response as a Gatling error, contributing to the reported error rate.

---

## Metrics Emitted

After each successful `POST /api/events`, Micrometer records:

| Metric | Type | Labels |
|--------|------|--------|
| `xa.transaction.duration` | Histogram | (none) |
| `http.server.requests` | Histogram | `uri=/api/events`, `method=POST`, `status=201` or `500` |

Both are scraped by Prometheus and visible in the Grafana dashboard.

---

## Out of Scope

- Authentication or authorization
- Request body validation beyond null/empty checks
- Pagination or batch submission
- Event retrieval (GET endpoints)
- Consumer-side acknowledgement or processing status
