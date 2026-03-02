package com.ledger.domain.model

import java.time.Instant

data class IdempotencyRecord(
    val tenantId: String,
    val key: String,
    val requestHash: String,
    val txId: String? = null,
    val status: IdempotencyStatus,
    val responseCode: Int? = null,
    val responseBody: String? = null,
    val createdAt: Instant = Instant.now()
)

enum class IdempotencyStatus {
    CREATED, REPLAYED, CONFLICT
}
