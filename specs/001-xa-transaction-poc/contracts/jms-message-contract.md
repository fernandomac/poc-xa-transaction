# Contract: JMS EventMessage

**Transport**: Apache Artemis queue `sample.events`
**Producer**: `EventProducerService.produceEvent(String payload)`
**Consumer**: `EventConsumerService` via `@JmsListener(destination="sample.events")`

## Message Format

**JMS message type**: `TextMessage`
**Body encoding**: UTF-8 JSON

```json
{
  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "payload": "arbitrary string content"
}
```

## Field Contracts

| Field | Type | Required | Invariant |
|---|---|---|---|
| `eventId` | UUID string | Yes | MUST equal the `id` of the `SampleEvent` saved in the same XA transaction |
| `payload` | String | Yes | MUST equal the `payload` field of the corresponding `SampleEvent` |

## Transactional Guarantee

The message is sent inside an XA transaction. It MUST NOT be delivered to the consumer
unless the XA transaction commits. If the transaction rolls back, no message appears on the
queue or in any dead-letter queue.

## Consumer Acknowledgement

The consumer listener container MUST be configured with `SESSION_TRANSACTED` acknowledge mode
so that message receipt participates in the JTA transaction context (if applicable) or at
minimum is acknowledged only after successful processing.

## Failure Behaviour

- Producer fault before DB commit → XA rollback → consumer receives nothing
- Consumer listener exception → message redelivered (standard JMS redelivery); consumer
  should be idempotent but for this POC redelivery handling is out of scope
