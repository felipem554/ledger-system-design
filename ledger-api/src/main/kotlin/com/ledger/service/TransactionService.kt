package com.ledger.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.*
import com.ledger.api.exception.*
import com.ledger.domain.model.*
import com.ledger.observability.LedgerMetrics
import com.ledger.repository.mongo.TransactionProjectionRepository
import com.ledger.repository.postgres.AccountRepository
import com.ledger.repository.postgres.AccountStateRepository
import com.ledger.repository.postgres.OutboxRepository
import com.ledger.repository.postgres.TransactionRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class TransactionService(
    private val txRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
    private val accountStateRepo: AccountStateRepository,
    private val outboxRepo: OutboxRepository,
    private val idempotencyService: IdempotencyService,
    private val projectionRepo: TransactionProjectionRepository,
    private val objectMapper: ObjectMapper,
    private val metrics: LedgerMetrics
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun postTransaction(
        tenantId: String,
        idempotencyKey: String,
        request: TransactionRequest,
        requestBodyRaw: String
    ): TransactionCreatedResponse {
        val startTime = System.nanoTime()
        try {
            // 1. Idempotency check
            idempotencyService.checkAndReserve(tenantId, idempotencyKey, requestBodyRaw)

            // 2. Validate balance (debits == credits)
            val debitTotal = request.entries.filter { it.direction == Direction.DEBIT }.sumOf { it.amountMinor }
            val creditTotal = request.entries.filter { it.direction == Direction.CREDIT }.sumOf { it.amountMinor }
            if (debitTotal != creditTotal) {
                throw UnbalancedTransactionException(debitTotal, creditTotal)
            }

            // 3. Validate all accounts exist and are open
            val accountIds = request.entries.map { it.accountId }.distinct()
            for (accountId in accountIds) {
                val account = accountRepo.findById(tenantId, accountId)
                    ?: throw AccountNotFoundException(accountId)
                if (account.status == AccountStatus.CLOSED) {
                    throw AccountClosedException(accountId)
                }
                if (account.currency != request.currency) {
                    throw BalanceValidationException("Currency mismatch: account ${account.id} is ${account.currency}, transaction is ${request.currency}")
                }
            }

            // 4. Create transaction
            val txId = UUID.randomUUID().toString()
            val occurredAt = request.timestamp ?: Instant.now()
            MDC.put("txId", txId)

            val transaction = Transaction(
                txId = txId,
                tenantId = tenantId,
                occurredAt = occurredAt,
                currency = request.currency,
                externalRef = request.externalRef,
                entries = request.entries.map { e ->
                    Entry(
                        tenantId = tenantId, txId = txId,
                        accountId = e.accountId, direction = e.direction,
                        amountMinor = e.amountMinor, createdAt = occurredAt
                    )
                },
                metadata = request.metadata
            )

            txRepo.insertTransaction(transaction)
            txRepo.insertEntries(transaction.entries)

            // 5. Update account balances atomically
            for (entry in transaction.entries) {
                val delta = when (entry.direction) {
                    Direction.DEBIT -> -entry.amountMinor
                    Direction.CREDIT -> entry.amountMinor
                }
                accountStateRepo.updateBalance(tenantId, entry.accountId, delta, txId)
            }

            // 6. Write outbox event
            val eventPayload = buildEventPayload(transaction, "TransactionPosted")
            outboxRepo.insert(
                OutboxEvent(
                    tenantId = tenantId,
                    eventType = "TransactionPosted",
                    payload = eventPayload
                )
            )

            // 7. Update idempotency record
            val response = TransactionCreatedResponse(txId, tenantId, TransactionStatus.POSTED, occurredAt)
            idempotencyService.recordSuccess(
                tenantId, idempotencyKey, txId, 201,
                objectMapper.writeValueAsString(response)
            )

            metrics.recordPosting("success")
            log.info("Transaction posted: txId={}", txId)
            return response

        } catch (e: OptimisticLockingFailureException) {
            metrics.recordPosting("concurrency_conflict")
            throw ConcurrencyConflictException(e.message ?: "Concurrency conflict during posting")
        } catch (e: IdempotencyReplayException) {
            metrics.recordIdempotencyHit()
            throw e
        } finally {
            metrics.recordPostingLatency(System.nanoTime() - startTime)
            MDC.remove("txId")
        }
    }

    @Transactional
    fun reverseTransaction(
        tenantId: String,
        txId: String,
        idempotencyKey: String
    ): TransactionCreatedResponse {
        // 1. Find original transaction
        val original = txRepo.findById(tenantId, txId)
            ?: throw TransactionNotFoundException(txId)
        if (original.status == TransactionStatus.REVERSED) {
            throw TransactionAlreadyReversedException(txId)
        }

        // 2. Check idempotency
        val requestBody = """{"reversalOf":"$txId"}"""
        idempotencyService.checkAndReserve(tenantId, idempotencyKey, requestBody)

        // 3. Create reversal transaction (swap debits/credits)
        val reversalId = UUID.randomUUID().toString()
        val occurredAt = Instant.now()

        val reversalEntries = original.entries.map { e ->
            Entry(
                tenantId = tenantId, txId = reversalId,
                accountId = e.accountId,
                direction = if (e.direction == Direction.DEBIT) Direction.CREDIT else Direction.DEBIT,
                amountMinor = e.amountMinor,
                createdAt = occurredAt
            )
        }

        val reversal = Transaction(
            txId = reversalId, tenantId = tenantId, occurredAt = occurredAt,
            currency = original.currency, status = TransactionStatus.POSTED,
            externalRef = "reversal_of_$txId", entries = reversalEntries,
            metadata = mapOf("reversalOf" to txId)
        )

        txRepo.insertTransaction(reversal)
        txRepo.insertEntries(reversalEntries)

        // 4. Update balances
        for (entry in reversalEntries) {
            val delta = when (entry.direction) {
                Direction.DEBIT -> -entry.amountMinor
                Direction.CREDIT -> entry.amountMinor
            }
            accountStateRepo.updateBalance(tenantId, entry.accountId, delta, reversalId)
        }

        // 5. Mark original as reversed
        txRepo.updateStatus(tenantId, txId, TransactionStatus.REVERSED)

        // 6. Write outbox events
        outboxRepo.insert(
            OutboxEvent(tenantId = tenantId, eventType = "TransactionReversed",
                payload = buildEventPayload(original.copy(status = TransactionStatus.REVERSED), "TransactionReversed"))
        )
        outboxRepo.insert(
            OutboxEvent(tenantId = tenantId, eventType = "TransactionPosted",
                payload = buildEventPayload(reversal, "TransactionPosted"))
        )

        val response = TransactionCreatedResponse(reversalId, tenantId, TransactionStatus.POSTED, occurredAt)
        idempotencyService.recordSuccess(
            tenantId, idempotencyKey, reversalId, 201,
            objectMapper.writeValueAsString(response)
        )

        log.info("Transaction reversed: original={} reversal={}", txId, reversalId)
        return response
    }

    fun postBatch(tenantId: String, request: BatchRequest): BatchResponse {
        val results = request.items.map { item ->
            try {
                val rawBody = objectMapper.writeValueAsString(item.transaction)
                val result = postTransaction(tenantId, item.idempotencyKey, item.transaction, rawBody)
                BatchResponseItem(item.idempotencyKey, BatchItemStatus.CREATED, result.txId)
            } catch (e: IdempotencyReplayException) {
                BatchResponseItem(item.idempotencyKey, BatchItemStatus.REPLAYED, e.txId)
            } catch (e: Exception) {
                BatchResponseItem(
                    item.idempotencyKey, BatchItemStatus.FAILED,
                    error = ErrorResponse(
                        code = e.javaClass.simpleName,
                        message = e.message ?: "Unknown error"
                    )
                )
            }
        }
        return BatchResponse(results)
    }

    fun getTransaction(tenantId: String, txId: String): TransactionResponse {
        return projectionRepo.findByTxId(tenantId, txId)
            ?: throw TransactionNotFoundException(txId)
    }

    fun listTransactions(
        tenantId: String,
        externalRef: String?,
        from: Instant?,
        to: Instant?,
        cursor: String?,
        limit: Int
    ): PagedResponse<TransactionResponse> {
        val safeLimit = limit.coerceIn(1, 500)
        val items = projectionRepo.findByTenant(tenantId, externalRef, from, to, cursor, safeLimit + 1)
        val hasMore = items.size > safeLimit
        val result = items.take(safeLimit)
        return PagedResponse(result, if (hasMore) result.last().txId else null)
    }

    private fun buildEventPayload(tx: Transaction, eventType: String): String {
        val event = mapOf(
            "schemaVersion" to 1,
            "eventId" to UUID.randomUUID().toString(),
            "eventType" to eventType,
            "tenantId" to tx.tenantId,
            "txId" to tx.txId,
            "occurredAt" to tx.occurredAt.toString(),
            "producer" to mapOf("service" to "ledger-api", "version" to "1.0.0"),
            "payload" to mapOf(
                "currency" to tx.currency,
                "externalRef" to tx.externalRef,
                "status" to tx.status.name,
                "entries" to tx.entries.map {
                    mapOf("accountId" to it.accountId, "direction" to it.direction.name, "amountMinor" to it.amountMinor)
                },
                "metadata" to tx.metadata
            )
        )
        return objectMapper.writeValueAsString(event)
    }
}
