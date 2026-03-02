package com.ledger.domain.model

import java.time.Instant

data class Account(
    val id: String,
    val tenantId: String,
    val name: String,
    val type: AccountType,
    val currency: String,
    val status: AccountStatus = AccountStatus.OPEN,
    val metadata: Map<String, Any>? = null,
    val createdAt: Instant = Instant.now()
)

enum class AccountType {
    ASSET, LIABILITY, INCOME, EXPENSE, EQUITY
}

enum class AccountStatus {
    OPEN, CLOSED
}
