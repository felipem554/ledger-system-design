package com.ledger.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.*
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
 * Integration tests for batch — focuses on multi-transaction balance
 * accumulation with real DB transactional boundaries.
 */
class BatchIT : BaseIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private val tenant = "test-tenant-batch-${UUID.randomUUID()}"
    private var accountA = ""
    private var accountB = ""

    @BeforeEach
    fun setup() {
        accountA = createAccount("Batch Account A", AccountType.ASSET)
        accountB = createAccount("Batch Account B", AccountType.LIABILITY)
    }

    @Test
    fun `batch should accumulate balance correctly in database`() {
        val items = (1..20).map {
            BatchTransactionItem(
                idempotencyKey = UUID.randomUUID().toString(),
                transaction = TransactionRequest(
                    currency = "EUR",
                    entries = listOf(
                        EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 10),
                        EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 10)
                    )
                )
            )
        }

        mockMvc.perform(
            post("/v1/transactions:batch")
                .header("X-Tenant-Id", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(BatchRequest(items)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.items[0].status").value("CREATED"))

        mockMvc.perform(
            get("/v1/accounts/$accountA/balance").header("X-Tenant-Id", tenant)
        )
            .andExpect(jsonPath("$.postedBalanceMinor").value(-200))
            .andExpect(jsonPath("$.version").value(20))
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
