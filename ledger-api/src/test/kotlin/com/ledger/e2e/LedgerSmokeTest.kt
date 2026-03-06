package com.ledger.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.*
import com.ledger.domain.model.AccountType
import com.ledger.domain.model.Direction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.*
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange
import java.util.UUID

/**
 * End-to-end smoke tests that run against the full ledger stack.
 *
 * These tests require the full Docker Compose environment to be running:
 *   docker compose -f docker/docker-compose.yml up -d --wait
 *
 * Then run:
 *   ./gradlew test --tests "com.ledger.e2e.*" -Dspring.profiles.active=e2e
 *
 * Or set E2E_BASE_URL to target a remote environment.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("e2e")
class LedgerSmokeTest {

    private val baseUrl = System.getenv("E2E_BASE_URL") ?: "http://localhost:8080"
    private val rest = RestTemplate()
    private val mapper = ObjectMapper().findAndRegisterModules()
    private val tenant = "e2e-smoke-${UUID.randomUUID()}"

    private var accountA: String = ""
    private var accountB: String = ""
    private var postedTxId: String = ""

    private fun headers(extras: Map<String, String> = emptyMap()): HttpHeaders {
        return HttpHeaders().apply {
            set("X-Tenant-Id", tenant)
            contentType = MediaType.APPLICATION_JSON
            extras.forEach { (k, v) -> set(k, v) }
        }
    }

    @Test
    @Order(1)
    fun `health check should respond`() {
        val response = rest.getForEntity("$baseUrl/healthz", Map::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    @Order(2)
    fun `should create two accounts`() {
        accountA = createAccount("E2E Asset Account", AccountType.ASSET)
        accountB = createAccount("E2E Liability Account", AccountType.LIABILITY)

        assertFalse(accountA.isBlank())
        assertFalse(accountB.isBlank())
    }

    @Test
    @Order(3)
    fun `should post a double-entry transaction`() {
        val request = TransactionRequest(
            currency = "EUR",
            entries = listOf(
                EntryInput(accountId = accountA, direction = Direction.DEBIT, amountMinor = 10000),
                EntryInput(accountId = accountB, direction = Direction.CREDIT, amountMinor = 10000)
            )
        )

        val response = rest.exchange<Map<*, *>>(
            "$baseUrl/v1/transactions",
            HttpMethod.POST,
            HttpEntity(mapper.writeValueAsString(request), headers(mapOf("Idempotency-Key" to UUID.randomUUID().toString())))
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        postedTxId = response.body!!["txId"] as String
        assertFalse(postedTxId.isBlank())
    }

    @Test
    @Order(4)
    fun `should verify balance after posting`() {
        val response = rest.exchange<Map<*, *>>(
            "$baseUrl/v1/accounts/$accountA/balance",
            HttpMethod.GET,
            HttpEntity<Unit>(headers())
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(-10000, (response.body!!["postedBalanceMinor"] as Number).toLong())
    }

    @Test
    @Order(5)
    fun `should reverse the transaction and restore balance`() {
        val reverseResponse = rest.exchange<Map<*, *>>(
            "$baseUrl/v1/transactions/$postedTxId:reverse",
            HttpMethod.POST,
            HttpEntity<Unit>(headers(mapOf("Idempotency-Key" to UUID.randomUUID().toString())))
        )
        assertEquals(HttpStatus.CREATED, reverseResponse.statusCode)

        val balanceResponse = rest.exchange<Map<*, *>>(
            "$baseUrl/v1/accounts/$accountA/balance",
            HttpMethod.GET,
            HttpEntity<Unit>(headers())
        )
        assertEquals(0L, (balanceResponse.body!!["postedBalanceMinor"] as Number).toLong())
    }

    @Test
    @Order(6)
    fun `should close account`() {
        val response = rest.exchange<Map<*, *>>(
            "$baseUrl/v1/accounts/$accountA:close",
            HttpMethod.POST,
            HttpEntity<Unit>(headers())
        )
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    private fun createAccount(name: String, type: AccountType): String {
        val request = CreateAccountRequest(name = name, type = type, currency = "EUR")
        val response = rest.exchange<Map<*, *>>(
            "$baseUrl/v1/accounts",
            HttpMethod.POST,
            HttpEntity(mapper.writeValueAsString(request), headers())
        )
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return response.body!!["id"] as String
    }
}
