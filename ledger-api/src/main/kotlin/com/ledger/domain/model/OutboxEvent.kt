package com.ledger.domain.model

import java.time.Instant
import java.util.UUID

data class OutboxEvent(
    val eventId: UUID = UUID.randomUUID(),
    val tenantId: String,
    val eventType: String,
    val payload: String,
    val createdAt: Instant = Instant.now(),
    val publishedAt: Instant? = null
)
