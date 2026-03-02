package com.ledger.api.dto

import com.ledger.domain.model.*
import java.time.Instant

data class ErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, Any>? = null,
    val traceId: String? = null
)

data class AccountResponse(
    val id: String,
    val tenantId: String,
    val name: String,
    val type: AccountType,
    val currency: String,
    val status: AccountStatus,
    val metadata: Map<String, Any>?,
    val createdAt: Instant
) {
    companion object {
        fun from(a: Account) = AccountResponse(
            id = a.id, tenantId = a.tenantId, name = a.name,
            type = a.type, currency = a.currency, status = a.status,
            metadata = a.metadata, createdAt = a.createdAt
        )
    }
}

data class BalanceResponse(
    val accountId: String,
    val tenantId: String,
    val currency: String,
    val postedBalanceMinor: Long,
    val version: Long,
    val asOf: BalanceAsOf?
)

data class BalanceAsOf(
    val txId: String?,
    val timestamp: Instant
)

data class TransactionCreatedResponse(
    val txId: String,
    val tenantId: String,
    val status: TransactionStatus,
    val occurredAt: Instant
)

data class TransactionResponse(
    val txId: String,
    val tenantId: String,
    val occurredAt: Instant,
    val currency: String,
    val status: TransactionStatus,
    val externalRef: String?,
    val entries: List<EntryResponse>,
    val metadata: Map<String, Any>?
)

data class EntryResponse(
    val accountId: String,
    val direction: Direction,
    val amountMinor: Long
)

data class PagedResponse<T>(
    val items: List<T>,
    val nextCursor: String? = null
)

data class BatchResponseItem(
    val idempotencyKey: String,
    val status: BatchItemStatus,
    val txId: String? = null,
    val error: ErrorResponse? = null
)

enum class BatchItemStatus {
    CREATED, REPLAYED, FAILED
}

data class BatchResponse(
    val items: List<BatchResponseItem>
)

data class IdempotencyStatusResponse(
    val key: String,
    val status: IdempotencyStatus,
    val txId: String?,
    val createdAt: Instant
)
