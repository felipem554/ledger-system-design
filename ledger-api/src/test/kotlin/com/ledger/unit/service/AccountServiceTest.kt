package com.ledger.unit.service

import com.ledger.api.dto.CreateAccountRequest
import com.ledger.api.exception.AccountClosedException
import com.ledger.api.exception.AccountNotFoundException
import com.ledger.domain.model.*
import com.ledger.repository.postgres.AccountRepository
import com.ledger.repository.postgres.AccountStateRepository
import com.ledger.service.AccountService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import java.time.Instant

class AccountServiceTest {

    private lateinit var accountRepo: AccountRepository
    private lateinit var accountStateRepo: AccountStateRepository
    private lateinit var service: AccountService

    private val tenantId = "unit-tenant"

    @BeforeEach
    fun setup() {
        accountRepo = mock()
        accountStateRepo = mock()
        service = AccountService(accountRepo, accountStateRepo)
    }

    @Test
    fun `should create account and return response`() {
        val request = CreateAccountRequest(
            name = "Test Account",
            type = AccountType.ASSET,
            currency = "EUR"
        )

        val result = service.createAccount(tenantId, request)

        assert(result.name == "Test Account")
        assert(result.type == AccountType.ASSET)
        assert(result.currency == "EUR")
        assert(result.status == AccountStatus.OPEN)
        assert(result.tenantId == tenantId)
        verify(accountRepo).insert(any())
    }

    @Test
    fun `should throw not found for missing account`() {
        whenever(accountRepo.findById(tenantId, "missing")).thenReturn(null)

        assertThrows<AccountNotFoundException> {
            service.getAccount(tenantId, "missing")
        }
    }

    @Test
    fun `should return account by id`() {
        val account = Account("acc-1", tenantId, "My Account", AccountType.LIABILITY, "USD")
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(account)

        val result = service.getAccount(tenantId, "acc-1")

        assert(result.id == "acc-1")
        assert(result.name == "My Account")
    }

    @Test
    fun `should close account`() {
        val account = Account("acc-1", tenantId, "Open Account", AccountType.ASSET, "EUR", AccountStatus.OPEN)
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(account)
        whenever(accountRepo.closeAccount(tenantId, "acc-1")).thenReturn(true)

        service.closeAccount(tenantId, "acc-1")

        verify(accountRepo).closeAccount(tenantId, "acc-1")
    }

    @Test
    fun `should throw when closing already closed account`() {
        val account = Account("acc-1", tenantId, "Closed", AccountType.ASSET, "EUR", AccountStatus.CLOSED)
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(account)

        assertThrows<AccountClosedException> {
            service.closeAccount(tenantId, "acc-1")
        }
    }

    @Test
    fun `should return zero balance for account without state`() {
        val account = Account("acc-1", tenantId, "New", AccountType.ASSET, "EUR")
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(account)
        whenever(accountStateRepo.findByAccountId(tenantId, "acc-1")).thenReturn(null)

        val result = service.getBalance(tenantId, "acc-1")

        assert(result.postedBalanceMinor == 0L)
        assert(result.version == 0L)
        assert(result.asOf == null)
    }

    @Test
    fun `should return balance from account state`() {
        val account = Account("acc-1", tenantId, "Active", AccountType.ASSET, "EUR")
        val state = AccountState(tenantId, "acc-1", 5000, 10, "tx-abc", Instant.now())
        whenever(accountRepo.findById(tenantId, "acc-1")).thenReturn(account)
        whenever(accountStateRepo.findByAccountId(tenantId, "acc-1")).thenReturn(state)

        val result = service.getBalance(tenantId, "acc-1")

        assert(result.postedBalanceMinor == 5000L)
        assert(result.version == 10L)
        assert(result.asOf?.txId == "tx-abc")
    }

    @Test
    fun `should list accounts with pagination`() {
        val accounts = (1..6).map {
            Account("acc-$it", tenantId, "Account $it", AccountType.ASSET, "EUR")
        }
        whenever(accountRepo.findByTenant(eq(tenantId), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(accounts)

        val result = service.listAccounts(tenantId, null, null, null, null, 5)

        assert(result.items.size == 5)
        assert(result.nextCursor != null)
    }

    @Test
    fun `should return no cursor when items fit in page`() {
        val accounts = (1..3).map {
            Account("acc-$it", tenantId, "Account $it", AccountType.ASSET, "EUR")
        }
        whenever(accountRepo.findByTenant(eq(tenantId), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(accounts)

        val result = service.listAccounts(tenantId, null, null, null, null, 5)

        assert(result.items.size == 3)
        assert(result.nextCursor == null)
    }
}
