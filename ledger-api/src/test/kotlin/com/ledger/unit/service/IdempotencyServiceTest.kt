package com.ledger.unit.service

import com.ledger.api.exception.IdempotencyConflictException
import com.ledger.api.exception.IdempotencyReplayException
import com.ledger.domain.model.IdempotencyRecord
import com.ledger.domain.model.IdempotencyStatus
import com.ledger.repository.postgres.IdempotencyRepository
import com.ledger.service.IdempotencyService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import java.time.Instant

class IdempotencyServiceTest {

    private lateinit var repo: IdempotencyRepository
    private lateinit var service: IdempotencyService

    private val tenantId = "unit-tenant"

    @BeforeEach
    fun setup() {
        repo = mock()
        service = IdempotencyService(repo)
    }

    @Test
    fun `should reserve new idempotency key`() {
        whenever(repo.findByKey(tenantId, "new-key")).thenReturn(null)
        whenever(repo.insert(any())).thenReturn(true)

        val result = service.checkAndReserve(tenantId, "new-key", """{"data":"test"}""")

        assert(result != null)
        assert(result!!.key == "new-key")
        verify(repo).insert(any())
    }

    @Test
    fun `should replay when same key and same payload hash exists`() {
        val existingHash = sha256("""{"data":"test"}""")
        val record = IdempotencyRecord(
            tenantId = tenantId, key = "existing-key", requestHash = existingHash,
            txId = "tx-100", status = IdempotencyStatus.CREATED,
            responseCode = 201, responseBody = """{"txId":"tx-100"}"""
        )
        whenever(repo.findByKey(tenantId, "existing-key")).thenReturn(record)

        assertThrows<IdempotencyReplayException> {
            service.checkAndReserve(tenantId, "existing-key", """{"data":"test"}""")
        }
    }

    @Test
    fun `should conflict when same key but different payload hash`() {
        val record = IdempotencyRecord(
            tenantId = tenantId, key = "conflict-key", requestHash = "different-hash",
            status = IdempotencyStatus.CREATED
        )
        whenever(repo.findByKey(tenantId, "conflict-key")).thenReturn(record)

        assertThrows<IdempotencyConflictException> {
            service.checkAndReserve(tenantId, "conflict-key", """{"data":"different"}""")
        }
    }

    @Test
    fun `should handle race condition on insert failure`() {
        whenever(repo.findByKey(tenantId, "race-key")).thenReturn(null)
        whenever(repo.insert(any())).thenReturn(false)

        val raceRecord = IdempotencyRecord(
            tenantId = tenantId, key = "race-key", requestHash = sha256("""{"data":"race"}"""),
            txId = "tx-race", status = IdempotencyStatus.CREATED,
            responseCode = 201, responseBody = """{"txId":"tx-race"}"""
        )
        whenever(repo.findByKey(tenantId, "race-key"))
            .thenReturn(null)
            .thenReturn(raceRecord)

        // Second call after insert failure returns the race record
        whenever(repo.findByKey(tenantId, "race-key")).thenReturn(raceRecord)
        whenever(repo.insert(any())).thenReturn(false)

        assertThrows<IdempotencyReplayException> {
            service.checkAndReserve(tenantId, "race-key", """{"data":"race"}""")
        }
    }

    @Test
    fun `should record success`() {
        service.recordSuccess(tenantId, "key-1", "tx-1", 201, """{"ok":true}""")

        verify(repo).updateResponse(tenantId, "key-1", "tx-1", 201, """{"ok":true}""")
    }

    @Test
    fun `should find by key`() {
        val record = IdempotencyRecord(
            tenantId = tenantId, key = "lookup-key", requestHash = "hash",
            status = IdempotencyStatus.CREATED
        )
        whenever(repo.findByKey(tenantId, "lookup-key")).thenReturn(record)

        val result = service.findByKey(tenantId, "lookup-key")

        assert(result != null)
        assert(result!!.key == "lookup-key")
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
