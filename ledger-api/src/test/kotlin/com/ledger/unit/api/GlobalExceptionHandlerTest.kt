package com.ledger.unit.api

import com.ledger.api.dto.ErrorResponse
import com.ledger.api.exception.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `should return 409 for idempotency conflict`() {
        val response = handler.handleIdempotencyConflict(
            IdempotencyConflictException("Key already exists with different payload")
        )
        assert(response.statusCode == HttpStatus.CONFLICT)
        assert(response.body?.code == "IDEMPOTENCY_CONFLICT")
    }

    @Test
    fun `should return 409 for concurrency conflict`() {
        val response = handler.handleConcurrencyConflict(
            ConcurrencyConflictException("Optimistic lock failed")
        )
        assert(response.statusCode == HttpStatus.CONFLICT)
        assert(response.body?.code == "CONCURRENCY_CONFLICT")
    }

    @Test
    fun `should return 404 for account not found`() {
        val response = handler.handleAccountNotFound(AccountNotFoundException("acc-1"))
        assert(response.statusCode == HttpStatus.NOT_FOUND)
        assert(response.body?.code == "NOT_FOUND")
    }

    @Test
    fun `should return 404 for transaction not found`() {
        val response = handler.handleTransactionNotFound(TransactionNotFoundException("tx-1"))
        assert(response.statusCode == HttpStatus.NOT_FOUND)
        assert(response.body?.code == "NOT_FOUND")
    }

    @Test
    fun `should return 409 for closed account`() {
        val response = handler.handleAccountClosed(AccountClosedException("acc-1"))
        assert(response.statusCode == HttpStatus.CONFLICT)
        assert(response.body?.code == "ACCOUNT_CLOSED")
    }

    @Test
    fun `should return 400 for unbalanced transaction`() {
        val response = handler.handleUnbalanced(UnbalancedTransactionException(1000, 500))
        assert(response.statusCode == HttpStatus.BAD_REQUEST)
        assert(response.body?.code == "UNBALANCED_TRANSACTION")
    }

    @Test
    fun `should return 409 for already reversed transaction`() {
        val response = handler.handleAlreadyReversed(TransactionAlreadyReversedException("tx-1"))
        assert(response.statusCode == HttpStatus.CONFLICT)
        assert(response.body?.code == "ALREADY_REVERSED")
    }

    @Test
    fun `should return 500 for generic exception`() {
        val response = handler.handleGeneric(RuntimeException("unexpected"))
        assert(response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR)
        assert(response.body?.code == "INTERNAL_ERROR")
    }
}
