package com.ledger.domain.model

import java.time.Instant

data class Transaction(
    val txId: String,
    val tenantId: String,
    val occurredAt: Instant = Instant.now(),
    val currency: String,
    val status: TransactionStatus = TransactionStatus.POSTED,
    val externalRef: String? = null,
    val entries: List<Entry> = emptyList(),
    val metadata: Map<String, Any>? = null
)

enum class TransactionStatus {
    POSTED, REVERSED
}

enum class Direction {
    DEBIT, CREDIT
}
