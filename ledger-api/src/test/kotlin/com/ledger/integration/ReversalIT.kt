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
 * Integration tests for reversals — focuses on balance restoration
 * that requires real DB persistence verification.
 */
class ReversalIT : BaseIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private val tenant = "test-tenant-reversal-${UUID.randomUUID()}"
    private var accountA = ""
    private var accountB = ""

    @BeforeEach
    fun setup() {
        accountA = createAccount("Reversal Account A", AccountType.ASSET)
        accountB = createAccount("Reversal Account B", AccountType.LIABILITY)
    }

    @Test
    fun `reversal should restore balance to zero in database`() {
        val txId = postTransaction(3000)

        mockMvc.perform(
            get("/v1/accounts/$accountA/balance").header("X-Tenant-Id", tenant)
        ).andExpect(jsonPath("$.postedBalanceMinor").value(-3000))

        mockMvc.perform(
            post("/v1/transactions/$txId:reverse")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
        ).andExpect(status().isCreated)

        mockMvc.perform(
            get("/v1/accounts/$accountA/balance").header("X-Tenant-Id", tenant)
        ).andExpect(jsonPath("$.postedBalanceMinor").value(0))
    }

    @Test
    fun `double reversal should be rejected by database state`() {
        val txId = postTransaction(500)

        mockMvc.perform(
            post("/v1/transactions/$txId:reverse")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/v1/transactions/$txId:reverse")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
        ).andExpect(status().isConflict)
    }

    private fun postTransaction(amountMinor: Long): String {
        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = amountMinor),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = amountMinor)
            )
        )
        val result = mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readTree(result.response.contentAsString).get("txId").asText()
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
