package com.ledger.service

import com.ledger.api.dto.*
import com.ledger.api.exception.AccountClosedException
import com.ledger.api.exception.AccountNotFoundException
import com.ledger.domain.model.Account
import com.ledger.domain.model.AccountStatus
import com.ledger.domain.model.AccountType
import com.ledger.repository.postgres.AccountRepository
import com.ledger.repository.postgres.AccountStateRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AccountService(
    private val accountRepo: AccountRepository,
    private val accountStateRepo: AccountStateRepository
) {

    fun createAccount(tenantId: String, request: CreateAccountRequest): AccountResponse {
        val account = Account(
            id = UUID.randomUUID().toString(),
            tenantId = tenantId,
            name = request.name,
            type = request.type,
            currency = request.currency,
            metadata = request.metadata
        )
        accountRepo.insert(account)
        return AccountResponse.from(account)
    }

    fun getAccount(tenantId: String, accountId: String): AccountResponse {
        val account = accountRepo.findById(tenantId, accountId)
            ?: throw AccountNotFoundException(accountId)
        return AccountResponse.from(account)
    }

    fun listAccounts(
        tenantId: String,
        type: AccountType?,
        currency: String?,
        status: AccountStatus?,
        cursor: String?,
        limit: Int
    ): PagedResponse<AccountResponse> {
        val safeLimit = limit.coerceIn(1, 500)
        val accounts = accountRepo.findByTenant(tenantId, type, currency, status, cursor, safeLimit + 1)
        val hasMore = accounts.size > safeLimit
        val items = accounts.take(safeLimit).map { AccountResponse.from(it) }
        return PagedResponse(items, if (hasMore) items.last().id else null)
    }

    fun closeAccount(tenantId: String, accountId: String) {
        val account = accountRepo.findById(tenantId, accountId)
            ?: throw AccountNotFoundException(accountId)
        if (account.status == AccountStatus.CLOSED) {
            throw AccountClosedException(accountId)
        }
        accountRepo.closeAccount(tenantId, accountId)
    }

    fun getBalance(tenantId: String, accountId: String): BalanceResponse {
        val account = accountRepo.findById(tenantId, accountId)
            ?: throw AccountNotFoundException(accountId)
        val state = accountStateRepo.findByAccountId(tenantId, accountId)

        return BalanceResponse(
            accountId = accountId,
            tenantId = tenantId,
            currency = account.currency,
            postedBalanceMinor = state?.postedBalanceMinor ?: 0,
            version = state?.version ?: 0,
            asOf = state?.let {
                BalanceAsOf(txId = it.lastTxId, timestamp = it.updatedAt)
            }
        )
    }
}
