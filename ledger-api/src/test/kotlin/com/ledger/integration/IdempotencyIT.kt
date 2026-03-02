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

class IdempotencyIT : BaseIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private val tenant = "test-tenant-idempotency"
    private var accountA = ""
    private var accountB = ""

    @BeforeEach
    fun setup() {
        accountA = createAccount("Idemp Account A", AccountType.ASSET)
        accountB = createAccount("Idemp Account B", AccountType.LIABILITY)
    }

    @Test
    fun `same idempotency key with same payload should replay response`() {
        val key = UUID.randomUUID().toString()
        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 200),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 200)
            )
        )
        val body = objectMapper.writeValueAsString(request)

        // First request
        val first = mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andReturn()

        val firstTxId = objectMapper.readTree(first.response.contentAsString).get("txId").asText()

        // Replay with same key + same payload
        val second = mockMvc.perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andReturn()

        val secondTxId = objectMapper.readTree(second.response.contentAsString).get("txId").asText()

        assert(firstTxId == secondTxId) { "Replay should return same txId" }
    }

    @Test
    fun `same idempotency key with different payload should return 409`() {
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

        // Different payload with same key
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
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
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

        // Post three times with same key
        repeat(3) {
            mockMvc.perform(
                post("/v1/transactions")
                    .header("X-Tenant-Id", tenant)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
        }

        // Balance should only reflect ONE posting
        mockMvc.perform(
            get("/v1/accounts/$accountA/balance")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.postedBalanceMinor").value(-500))
            .andExpect(jsonPath("$.version").value(1))
    }

    @Test
    fun `should check idempotency key status`() {
        val key = UUID.randomUUID().toString()
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
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated)

        mockMvc.perform(
            get("/v1/idempotency/$key")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value(key))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.txId").exists())
    }

    @Test
    fun `high volume concurrent idempotency keys should all succeed without duplicates`() {
        val keys = (1..20).map { UUID.randomUUID().toString() }

        for (key in keys) {
            val request = TransactionRequest(
                currency = "EUR",
                entries = listOf(
                    EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 10),
                    EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 10)
                )
            )
            mockMvc.perform(
                post("/v1/transactions")
                    .header("X-Tenant-Id", tenant)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isCreated)
        }

        mockMvc.perform(
            get("/v1/accounts/$accountA/balance")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
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
