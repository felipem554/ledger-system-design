# ADR-004: At-least-once Event Delivery with Idempotent Consumers

## Context
Exactly-once end-to-end is complex across Kafka + Mongo.

## Decision
Use at-least-once delivery. Consumers implement idempotent upserts on `(tenantId, txId)`.

## Consequences
+ Simple, robust failure handling
+ Safe replay
- Consumers must be carefully designed to be idempotent
