package com.ledger.domain.model

import java.time.Instant

data class AccountState(
    val tenantId: String,
    val accountId: String,
    val postedBalanceMinor: Long = 0,
    val version: Long = 0,
    val lastTxId: String? = null,
    val updatedAt: Instant = Instant.now()
)
