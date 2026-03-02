package com.ledger.repository.postgres

import com.ledger.domain.model.IdempotencyRecord
import com.ledger.domain.model.IdempotencyStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class IdempotencyRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        IdempotencyRecord(
            tenantId = rs.getString("tenant_id"),
            key = rs.getString("key"),
            requestHash = rs.getString("request_hash"),
            txId = rs.getString("tx_id"),
            status = IdempotencyStatus.valueOf(rs.getString("status")),
            responseCode = rs.getObject("response_code") as? Int,
            responseBody = rs.getString("response_body"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    fun findByKey(tenantId: String, key: String): IdempotencyRecord? {
        return jdbc.query(
            "SELECT * FROM idempotency WHERE tenant_id = ? AND key = ?",
            rowMapper, tenantId, key
        ).firstOrNull()
    }

    fun insert(record: IdempotencyRecord): Boolean {
        return try {
            jdbc.update(
                """INSERT INTO idempotency (tenant_id, key, request_hash, tx_id, status, response_code, response_body, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, now())""",
                record.tenantId, record.key, record.requestHash, record.txId,
                record.status.name, record.responseCode, record.responseBody
            )
            true
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            false
        }
    }

    fun updateResponse(tenantId: String, key: String, txId: String, responseCode: Int, responseBody: String?) {
        jdbc.update(
            "UPDATE idempotency SET tx_id = ?, response_code = ?, response_body = ?, status = 'CREATED' WHERE tenant_id = ? AND key = ?",
            txId, responseCode, responseBody, tenantId, key
        )
    }
}
