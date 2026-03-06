package com.ledger.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.CreateAccountRequest
import com.ledger.domain.model.AccountType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

/**
 * Integration tests for accounts — focuses on persistence and cursor-based
 * pagination that require a real database.
 */
class AccountIT : BaseIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private val tenant = "test-tenant-account-${UUID.randomUUID()}"

    @Test
    fun `should persist account and retrieve it`() {
        val request = CreateAccountRequest(
            name = "Persisted Account",
            type = AccountType.ASSET,
            currency = "EUR",
            metadata = mapOf("owner" to "test")
        )

        val result = mockMvc.perform(
            post("/v1/accounts")
                .header("X-Tenant-Id", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andReturn()

        val id = objectMapper.readTree(result.response.contentAsString).get("id").asText()

        mockMvc.perform(
            get("/v1/accounts/$id")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.name").value("Persisted Account"))
            .andExpect(jsonPath("$.currency").value("EUR"))
    }

    @Test
    fun `should persist closed status across requests`() {
        val accountId = createAccount("Close Test Account", AccountType.EXPENSE)

        mockMvc.perform(
            post("/v1/accounts/$accountId:close")
                .header("X-Tenant-Id", tenant)
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/v1/accounts/$accountId")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(jsonPath("$.status").value("CLOSED"))

        mockMvc.perform(
            post("/v1/accounts/$accountId:close")
                .header("X-Tenant-Id", tenant)
        ).andExpect(status().isConflict)
    }

    @Test
    fun `should paginate accounts with cursor from database`() {
        repeat(5) { i ->
            createAccount("Paginate Account $i", AccountType.ASSET)
        }

        val page1 = mockMvc.perform(
            get("/v1/accounts")
                .header("X-Tenant-Id", tenant)
                .param("limit", "3")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.nextCursor").exists())
            .andReturn()

        val cursor = objectMapper.readTree(page1.response.contentAsString).get("nextCursor").asText()

        mockMvc.perform(
            get("/v1/accounts")
                .header("X-Tenant-Id", tenant)
                .param("cursor", cursor)
                .param("limit", "3")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
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
