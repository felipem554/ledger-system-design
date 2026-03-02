package com.ledger.unit.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.EntryInput
import com.ledger.api.dto.TransactionRequest
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
}
