package com.ledger.api.dto

import com.ledger.domain.model.AccountType
import com.ledger.domain.model.Direction
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateAccountRequest(
    @field:NotBlank val name: String,
    @field:NotNull val type: AccountType,
    @field:NotBlank @field:Size(min = 3, max = 3) val currency: String,
    val metadata: Map<String, Any>? = null
)

data class EntryInput(
    @field:NotBlank val accountId: String,
    @field:NotNull val direction: Direction,
    @field:Min(1) val amountMinor: Long
)

data class TransactionRequest(
    val timestamp: Instant? = null,
    val externalRef: String? = null,
    @field:NotBlank @field:Size(min = 3, max = 3) val currency: String,
    @field:Valid @field:Size(min = 2, max = 50) val entries: List<EntryInput>,
    val metadata: Map<String, Any>? = null
)

data class BatchTransactionItem(
    @field:NotBlank val idempotencyKey: String,
    @field:Valid @field:NotNull val transaction: TransactionRequest
)

data class BatchRequest(
    @field:Valid @field:Size(min = 1, max = 500) val items: List<BatchTransactionItem>
)
