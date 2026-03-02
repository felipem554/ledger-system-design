package com.ledger.service

import com.ledger.api.exception.IdempotencyConflictException
import com.ledger.api.exception.IdempotencyReplayException
import com.ledger.domain.model.IdempotencyRecord
import com.ledger.domain.model.IdempotencyStatus
import com.ledger.repository.postgres.IdempotencyRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class IdempotencyService(private val repo: IdempotencyRepository) {

    fun checkAndReserve(tenantId: String, key: String, requestBody: String): IdempotencyRecord? {
        val requestHash = sha256(requestBody)

        val existing = repo.findByKey(tenantId, key)
        if (existing != null) {
            if (existing.requestHash == requestHash) {
                throw IdempotencyReplayException(existing.txId, existing.responseCode, existing.responseBody)
            } else {
                throw IdempotencyConflictException(
                    "Idempotency key '$key' already used with different request payload"
                )
            }
        }

        val record = IdempotencyRecord(
            tenantId = tenantId,
            key = key,
            requestHash = requestHash,
            status = IdempotencyStatus.CREATED
        )

        val inserted = repo.insert(record)
        if (!inserted) {
            // Race condition: another request inserted first
            val raceCheck = repo.findByKey(tenantId, key)!!
            if (raceCheck.requestHash == requestHash) {
                throw IdempotencyReplayException(raceCheck.txId, raceCheck.responseCode, raceCheck.responseBody)
            } else {
                throw IdempotencyConflictException(
                    "Idempotency key '$key' already used with different request payload"
                )
            }
        }
        return record
    }

    fun recordSuccess(tenantId: String, key: String, txId: String, responseCode: Int, responseBody: String?) {
        repo.updateResponse(tenantId, key, txId, responseCode, responseBody)
    }

    fun findByKey(tenantId: String, key: String): IdempotencyRecord? {
        return repo.findByKey(tenantId, key)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
