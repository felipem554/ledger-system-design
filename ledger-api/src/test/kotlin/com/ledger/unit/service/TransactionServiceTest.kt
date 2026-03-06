package com.ledger.unit.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.*
import com.ledger.api.exception.*
import com.ledger.domain.model.*
import com.ledger.observability.LedgerMetrics
import com.ledger.repository.mongo.TransactionProjectionRepository
import com.ledger.repository.postgres.*
import com.ledger.service.IdempotencyService
import com.ledger.service.TransactionService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import java.time.Instant

class TransactionServiceTest {

    private lateinit var txRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var accountStateRepo: AccountStateRepository
    private lateinit var outboxRepo: OutboxRepository
    private lateinit var idempotencyService: IdempotencyService
    private lateinit var projectionRepo: TransactionProjectionRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var metrics: LedgerMetrics
    private lateinit var service: TransactionService

    private val tenantId = "unit-tenant"

    @BeforeEach
    fun setup() {
        txRepo = mock()
        accountRepo = mock()
        accountStateRepo = mock()
        outboxRepo = mock()
        idempotencyService = mock()
        projectionRepo = mock()
        objectMapper = ObjectMapper().findAndRegisterModules()
        metrics = mock()

        service = TransactionService(
            txRepo, accountRepo, accountStateRepo, outboxRepo,
            idempotencyService, projectionRepo, objectMapper, metrics
        )
    }

    @Test
    fun `should reject unbalanced transaction`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput("acc-1", Direction.DEBIT, 1000),
                EntryInput("acc-2", Direction.CREDIT, 500)
            )
        )

        assertThrows<UnbalancedTransactionException> {
            service.postTransaction(tenantId, "key-1", request, "{}")
        }
    }

    @Test
    fun `should reject transaction against non-existent account`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(null)

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput("acc-1", Direction.DEBIT, 100),
                EntryInput("acc-2", Direction.CREDIT, 100)
            )
        )

        assertThrows<AccountNotFoundException> {
            service.postTransaction(tenantId, "key-2", request, "{}")
        }
    }

    @Test
    fun `should reject transaction against closed account`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(
            Account("acc-1", tenantId, "Closed", AccountType.ASSET, "EUR", AccountStatus.CLOSED)
        )

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput("acc-1", Direction.DEBIT, 100),
                EntryInput("acc-2", Direction.CREDIT, 100)
            )
        )

        assertThrows<AccountClosedException> {
            service.postTransaction(tenantId, "key-3", request, "{}")
        }
    }

    @Test
    fun `should reject currency mismatch`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(
            Account("acc-1", tenantId, "USD Account", AccountType.ASSET, "USD", AccountStatus.OPEN)
        )

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput("acc-1", Direction.DEBIT, 100),
                EntryInput("acc-2", Direction.CREDIT, 100)
            )
        )

        assertThrows<BalanceValidationException> {
            service.postTransaction(tenantId, "key-4", request, "{}")
        }
    }

    @Test
    fun `should post successful balanced transaction`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(
            Account("acc-1", tenantId, "Asset", AccountType.ASSET, "EUR", AccountStatus.OPEN)
        )
        whenever(accountRepo.findById(tenantId, "acc-2")).thenReturn(
            Account("acc-2", tenantId, "Liability", AccountType.LIABILITY, "EUR", AccountStatus.OPEN)
        )

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput("acc-1", Direction.DEBIT, 1000),
                EntryInput("acc-2", Direction.CREDIT, 1000)
            )
        )

        val result = service.postTransaction(tenantId, "key-5", request, "{}")

        assert(result.status == TransactionStatus.POSTED)
        assert(result.tenantId == tenantId)

        verify(txRepo).insertTransaction(any())
        verify(txRepo).insertEntries(any())
        verify(accountStateRepo, times(2)).updateBalance(eq(tenantId), any(), any(), any())
        verify(outboxRepo).insert(any())
        verify(idempotencyService).recordSuccess(eq(tenantId), eq("key-5"), any(), eq(201), any())
    }

    @Test
    fun `should replay idempotent request`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any()))
            .thenThrow(IdempotencyReplayException("tx-123", 201, """{"txId":"tx-123"}"""))

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput("acc-1", Direction.DEBIT, 100),
                EntryInput("acc-2", Direction.CREDIT, 100)
            )
        )

        assertThrows<IdempotencyReplayException> {
            service.postTransaction(tenantId, "dup-key", request, "{}")
        }
        verify(metrics).recordIdempotencyHit()
    }

    @Test
    fun `should reject reversal of non-existent transaction`() {
        whenever(txRepo.findById(tenantId, "nonexistent")).thenReturn(null)

        assertThrows<TransactionNotFoundException> {
            service.reverseTransaction(tenantId, "nonexistent", "rev-key")
        }
    }

    @Test
    fun `should reject double reversal`() {
        whenever(txRepo.findById(tenantId, "tx-1")).thenReturn(
            Transaction("tx-1", tenantId, Instant.now(), "EUR", TransactionStatus.REVERSED)
        )

        assertThrows<TransactionAlreadyReversedException> {
            service.reverseTransaction(tenantId, "tx-1", "rev-key-2")
        }
    }

    @Test
    fun `should reverse transaction and swap entry directions`() {
        val originalEntries = listOf(
            Entry(tenantId = tenantId, txId = "tx-1", accountId = "acc-1", direction = Direction.DEBIT, amountMinor = 500),
            Entry(tenantId = tenantId, txId = "tx-1", accountId = "acc-2", direction = Direction.CREDIT, amountMinor = 500)
        )
        whenever(txRepo.findById(tenantId, "tx-1")).thenReturn(
            Transaction("tx-1", tenantId, Instant.now(), "EUR", TransactionStatus.POSTED, entries = originalEntries)
        )
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)

        val result = service.reverseTransaction(tenantId, "tx-1", "rev-key")

        assert(result.status == TransactionStatus.POSTED)

        val entriesCaptor = argumentCaptor<List<Entry>>()
        verify(txRepo).insertEntries(entriesCaptor.capture())
        val reversalEntries = entriesCaptor.firstValue
        assert(reversalEntries[0].direction == Direction.CREDIT) { "DEBIT should become CREDIT" }
        assert(reversalEntries[1].direction == Direction.DEBIT) { "CREDIT should become DEBIT" }

        verify(txRepo).updateStatus(tenantId, "tx-1", TransactionStatus.REVERSED)
        verify(outboxRepo, times(2)).insert(any())
    }

    @Test
    fun `should wrap optimistic locking failure as concurrency conflict`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(
            Account("acc-1", tenantId, "Asset", AccountType.ASSET, "EUR", AccountStatus.OPEN)
        )
        whenever(accountRepo.findById(tenantId, "acc-2")).thenReturn(
            Account("acc-2", tenantId, "Liability", AccountType.LIABILITY, "EUR", AccountStatus.OPEN)
        )
        whenever(accountStateRepo.updateBalance(any(), any(), any(), any()))
            .thenThrow(org.springframework.dao.OptimisticLockingFailureException("version mismatch"))

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput("acc-1", Direction.DEBIT, 100),
                EntryInput("acc-2", Direction.CREDIT, 100)
            )
        )

        assertThrows<ConcurrencyConflictException> {
            service.postTransaction(tenantId, "key-conflict", request, "{}")
        }
        verify(metrics).recordPosting("concurrency_conflict")
    }

    @Test
    fun `postBatch should return CREATED for each successful item`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)
        whenever(accountRepo.findById(eq(tenantId), any())).thenReturn(
            Account("acc-1", tenantId, "Asset", AccountType.ASSET, "EUR", AccountStatus.OPEN)
        )

        val request = BatchRequest(
            items = listOf(
                BatchTransactionItem(
                    idempotencyKey = "batch-key-1",
                    transaction = TransactionRequest(
                        currency = "EUR",
                        entries = listOf(
                            EntryInput("acc-1", Direction.DEBIT, 100),
                            EntryInput("acc-1", Direction.CREDIT, 100)
                        )
                    )
                )
            )
        )

        val result = service.postBatch(tenantId, request)

        assert(result.items.size == 1)
        assert(result.items[0].status == BatchItemStatus.CREATED)
        assert(result.items[0].txId != null)
    }

    @Test
    fun `postBatch should return REPLAYED for idempotent replay`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any()))
            .thenThrow(IdempotencyReplayException("tx-existing", 201, null))

        val request = BatchRequest(
            items = listOf(
                BatchTransactionItem(
                    idempotencyKey = "dup-batch-key",
                    transaction = TransactionRequest(
                        currency = "EUR",
                        entries = listOf(
                            EntryInput("acc-1", Direction.DEBIT, 100),
                            EntryInput("acc-2", Direction.CREDIT, 100)
                        )
                    )
                )
            )
        )

        val result = service.postBatch(tenantId, request)

        assert(result.items[0].status == BatchItemStatus.REPLAYED)
        assert(result.items[0].txId == "tx-existing")
    }

    @Test
    fun `postBatch should return FAILED on exception`() {
        whenever(idempotencyService.checkAndReserve(any(), any(), any())).thenReturn(null)
        whenever(accountRepo.findById(any(), any())).thenReturn(null)

        val request = BatchRequest(
            items = listOf(
                BatchTransactionItem(
                    idempotencyKey = "fail-key",
                    transaction = TransactionRequest(
                        currency = "EUR",
                        entries = listOf(
                            EntryInput("nonexistent", Direction.DEBIT, 100),
                            EntryInput("also-nonexistent", Direction.CREDIT, 100)
                        )
                    )
                )
            )
        )

        val result = service.postBatch(tenantId, request)

        assert(result.items[0].status == BatchItemStatus.FAILED)
        assert(result.items[0].error != null)
    }

    @Test
    fun `getTransaction should throw when not found in projection`() {
        whenever(projectionRepo.findByTxId(tenantId, "missing")).thenReturn(null)

        assertThrows<TransactionNotFoundException> {
            service.getTransaction(tenantId, "missing")
        }
    }

    @Test
    fun `listTransactions should coerce limit to valid range`() {
        whenever(projectionRepo.findByTenant(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(emptyList())

        service.listTransactions(tenantId, null, null, null, null, 0)
        verify(projectionRepo).findByTenant(tenantId, null, null, null, null, 2) // coerced to 1 + 1

        service.listTransactions(tenantId, null, null, null, null, 999)
        verify(projectionRepo).findByTenant(tenantId, null, null, null, null, 501) // coerced to 500 + 1
    }
}
