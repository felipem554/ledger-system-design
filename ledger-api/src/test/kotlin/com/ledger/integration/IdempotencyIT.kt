package com.ledger.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.CreateAccountRequest
import com.ledger.api.dto.EntryInput
import com.ledger.api.dto.TransactionRequest
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

/**
 * Integration tests for idempotency — focuses on scenarios that require
 * real DB persistence (balance not double-counted, conflict detection).
 */
class IdempotencyIT : BaseIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private val tenant = "test-tenant-idempotency-${UUID.randomUUID()}"
    private var accountA = ""
    private var accountB = ""

    @BeforeEach
    fun setup() {
        accountA = createAccount("Idemp Account A", AccountType.ASSET)
        accountB = createAccount("Idemp Account B", AccountType.LIABILITY)
    }

    @Test
    fun `should not double-count balance on idempotent replay`() {
        val key = UUID.randomUUID().toString()
        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 500),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 500)
            )
        )
        val body = objectMapper.writeValueAsString(request)

        repeat(3) {
            mockMvc.perform(
                post("/v1/transactions")
                    .header("X-Tenant-Id", tenant)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
        }

        mockMvc.perform(
            get("/v1/accounts/$accountA/balance").header("X-Tenant-Id", tenant)
        )
            .andExpect(jsonPath("$.postedBalanceMinor").value(-500))
            .andExpect(jsonPath("$.version").value(1))
    }

    @Test
    fun `different payload with same key should conflict in database`() {
        val key = UUID.randomUUID().toString()

        val request1 = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 100),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 100)
            )
        )

        mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request1))
        ).andExpect(status().isCreated)

        val request2 = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 999),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 999)
            )
        )

        mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request2))
        ).andExpect(status().isConflict)
    }

    private fun createAccount(name: String, type: AccountType): String {
        val request = CreateAccountRequest(name = name, type = type, currency = "EUR")
        val result = mockMvc.perform(
            post("/v1/accounts")
                .header("X-Tenant-Id", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readTree(result.response.contentAsString).get("id").asText()
    }
}
