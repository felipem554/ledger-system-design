package com.ledger.domain.model

import java.time.Instant

data class Entry(
    val id: Long? = null,
    val tenantId: String,
    val txId: String,
    val accountId: String,
    val direction: Direction,
    val amountMinor: Long,
    val createdAt: Instant = Instant.now()
)
