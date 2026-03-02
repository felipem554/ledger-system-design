package com.ledger.api.exception

import com.ledger.api.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IdempotencyConflictException::class)
    fun handleIdempotencyConflict(ex: IdempotencyConflictException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse("IDEMPOTENCY_CONFLICT", ex.message ?: "Idempotency conflict", traceId = MDC.get("traceId"))
        )
    }

    @ExceptionHandler(ConcurrencyConflictException::class)
    fun handleConcurrencyConflict(ex: ConcurrencyConflictException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse("CONCURRENCY_CONFLICT", ex.message ?: "Concurrency conflict", traceId = MDC.get("traceId"))
        )
    }

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleAccountNotFound(ex: AccountNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse("NOT_FOUND", ex.message ?: "Not found", traceId = MDC.get("traceId"))
        )
    }

    @ExceptionHandler(TransactionNotFoundException::class)
    fun handleTransactionNotFound(ex: TransactionNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse("NOT_FOUND", ex.message ?: "Not found", traceId = MDC.get("traceId"))
        )
    }

    @ExceptionHandler(AccountClosedException::class)
    fun handleAccountClosed(ex: AccountClosedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse("ACCOUNT_CLOSED", ex.message ?: "Account is closed", traceId = MDC.get("traceId"))
        )
    }

    @ExceptionHandler(UnbalancedTransactionException::class)
    fun handleUnbalanced(ex: UnbalancedTransactionException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse("UNBALANCED_TRANSACTION", ex.message ?: "Unbalanced transaction", traceId = MDC.get("traceId"))
        )
    }

    @ExceptionHandler(TransactionAlreadyReversedException::class)
    fun handleAlreadyReversed(ex: TransactionAlreadyReversedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse("ALREADY_REVERSED", ex.message ?: "Already reversed", traceId = MDC.get("traceId"))
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse("VALIDATION_ERROR", "Request validation failed", details = details, traceId = MDC.get("traceId"))
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse("INTERNAL_ERROR", "Internal server error", traceId = MDC.get("traceId"))
        )
    }
}
