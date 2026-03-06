package com.ledger.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.CreateAccountRequest
import com.ledger.api.dto.TransactionRequest
import com.ledger.api.dto.EntryInput
import com.ledger.domain.model.AccountType
import com.ledger.domain.model.Direction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

class LedgerPostingIT : BaseIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private val tenant = "test-tenant-posting-${UUID.randomUUID()}"
    private var accountA = ""
    private var accountB = ""

    @BeforeEach
    fun setup() {
        accountA = createAccount("Account A", AccountType.ASSET)
        accountB = createAccount("Account B", AccountType.LIABILITY)
    }

    @Test
    fun `should post balanced double-entry transaction`() {
        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 1000),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 1000)
            ),
            metadata = mapOf("test" to "posting")
        )

        mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.txId").exists())
            .andExpect(jsonPath("$.status").value("POSTED"))
    }

    @Test
    fun `should reject unbalanced transaction`() {
        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 1000),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 500)
            )
        )

        mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("UNBALANCED_TRANSACTION"))
    }

    @Test
    fun `should update account balance after posting`() {
        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 5000),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 5000)
            )
        )

        mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated)

        mockMvc.perform(
            get("/v1/accounts/$accountA/balance")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.postedBalanceMinor").value(-5000))
            .andExpect(jsonPath("$.version").value(1))
    }

    @Test
    fun `should handle multiple transactions and accumulate balance`() {
        repeat(5) {
            val request = TransactionRequest(
                currency = "EUR",
                entries = listOf(
                    EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 100),
                    EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 100)
                )
            )
            mockMvc.perform(
                post("/v1/transactions")
                    .header("X-Tenant-Id", tenant)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isCreated)
        }

        mockMvc.perform(
            get("/v1/accounts/$accountA/balance")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.postedBalanceMinor").value(-500))
            .andExpect(jsonPath("$.version").value(5))
    }

    @Test
    fun `should reject transaction against closed account`() {
        val closedAccount = createAccount("Closed Account", AccountType.EXPENSE)
        mockMvc.perform(
            post("/v1/accounts/$closedAccount:close")
                .header("X-Tenant-Id", tenant)
        ).andExpect(status().isOk)

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 100),
                EntryInput(accountId = closedAccount, direction = Direction.CREDIT, amountMinor = 100)
            )
        )

        mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isConflict)
    }

    @Test
    fun `should reject transaction with currency mismatch`() {
        val usdAccount = createAccount("USD Account", AccountType.ASSET, "USD")

        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 100),
                EntryInput(accountId = usdAccount, direction = Direction.CREDIT, amountMinor = 100)
            )
        )

        mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isInternalServerError)
    }

    private fun createAccount(name: String, type: AccountType, currency: String = "EUR"): String {
        val request = CreateAccountRequest(name = name, type = type, currency = currency)
        val result = mockMvc.perform(
            post("/v1/accounts")
                .header("X-Tenant-Id", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString).get("id").asText()
    }
}
