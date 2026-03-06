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

class AccountIT : BaseIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private val tenant = "test-tenant-account-${UUID.randomUUID()}"

    @Test
    fun `should create account`() {
        val request = CreateAccountRequest(
            name = "Test Asset Account",
            type = AccountType.ASSET,
            currency = "EUR",
            metadata = mapOf("owner" to "test")
        )

        mockMvc.perform(
            post("/v1/accounts")
                .header("X-Tenant-Id", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.tenantId").value(tenant))
            .andExpect(jsonPath("$.name").value("Test Asset Account"))
            .andExpect(jsonPath("$.type").value("ASSET"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.status").value("OPEN"))
    }

    @Test
    fun `should get account by id`() {
        val accountId = createAccount("Get Test Account", AccountType.LIABILITY)

        mockMvc.perform(
            get("/v1/accounts/$accountId")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(accountId))
            .andExpect(jsonPath("$.name").value("Get Test Account"))
    }

    @Test
    fun `should return 404 for non-existent account`() {
        mockMvc.perform(
            get("/v1/accounts/nonexistent")
                .header("X-Tenant-Id", tenant)
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `should list accounts with pagination`() {
        repeat(5) { i ->
            createAccount("List Account $i", AccountType.ASSET)
        }

        mockMvc.perform(
            get("/v1/accounts")
                .header("X-Tenant-Id", tenant)
                .param("limit", "3")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.nextCursor").exists())
    }

    @Test
    fun `should close account`() {
        val accountId = createAccount("Close Test Account", AccountType.EXPENSE)

        mockMvc.perform(
            post("/v1/accounts/$accountId:close")
                .header("X-Tenant-Id", tenant)
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/v1/accounts/$accountId")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CLOSED"))
    }

    @Test
    fun `should conflict when closing already closed account`() {
        val accountId = createAccount("Double Close Account", AccountType.INCOME)

        mockMvc.perform(
            post("/v1/accounts/$accountId:close")
                .header("X-Tenant-Id", tenant)
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/v1/accounts/$accountId:close")
                .header("X-Tenant-Id", tenant)
        ).andExpect(status().isConflict)
    }

    @Test
    fun `should get zero balance for new account`() {
        val accountId = createAccount("Zero Balance Account", AccountType.ASSET)

        mockMvc.perform(
            get("/v1/accounts/$accountId/balance")
                .header("X-Tenant-Id", tenant)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.postedBalanceMinor").value(0))
            .andExpect(jsonPath("$.version").value(0))
    }

    @Test
    fun `should filter accounts by type`() {
        createAccount("Filter Asset 1", AccountType.ASSET)
        createAccount("Filter Asset 2", AccountType.ASSET)
        createAccount("Filter Liability", AccountType.LIABILITY)

        mockMvc.perform(
            get("/v1/accounts")
                .header("X-Tenant-Id", tenant)
                .param("type", "ASSET")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
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
