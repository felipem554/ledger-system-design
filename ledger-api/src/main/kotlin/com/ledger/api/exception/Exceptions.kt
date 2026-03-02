package com.ledger.api.exception

class IdempotencyConflictException(message: String) : RuntimeException(message)

class IdempotencyReplayException(
    val txId: String?,
    val responseCode: Int?,
    val responseBody: String?
) : RuntimeException("Idempotent replay for txId=$txId")

class ConcurrencyConflictException(message: String) : RuntimeException(message)

class AccountNotFoundException(accountId: String) : RuntimeException("Account not found: $accountId")

class AccountClosedException(accountId: String) : RuntimeException("Account is closed: $accountId")

class TransactionNotFoundException(txId: String) : RuntimeException("Transaction not found: $txId")

class BalanceValidationException(message: String) : RuntimeException(message)

class UnbalancedTransactionException(debitTotal: Long, creditTotal: Long) :
    RuntimeException("Transaction is unbalanced: debits=$debitTotal credits=$creditTotal")

class TransactionAlreadyReversedException(txId: String) :
    RuntimeException("Transaction already reversed: $txId")
