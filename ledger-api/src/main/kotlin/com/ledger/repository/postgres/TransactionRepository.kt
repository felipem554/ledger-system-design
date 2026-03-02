package com.ledger.repository.postgres

import com.ledger.domain.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class TransactionRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper
) {

    private val txRowMapper = RowMapper { rs: ResultSet, _ ->
        Transaction(
            txId = rs.getString("tx_id"),
            tenantId = rs.getString("tenant_id"),
            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            currency = rs.getString("currency"),
            status = TransactionStatus.valueOf(rs.getString("status")),
            externalRef = rs.getString("external_ref"),
            metadata = rs.getString("metadata")?.let {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(it, Map::class.java) as Map<String, Any>
            }
        )
    }

    private val entryRowMapper = RowMapper { rs: ResultSet, _ ->
        Entry(
            id = rs.getLong("id"),
            tenantId = rs.getString("tenant_id"),
            txId = rs.getString("tx_id"),
            accountId = rs.getString("account_id"),
            direction = Direction.valueOf(rs.getString("direction")),
            amountMinor = rs.getLong("amount_minor"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    fun insertTransaction(tx: Transaction) {
        jdbc.update(
            """INSERT INTO transactions_min (tx_id, tenant_id, occurred_at, currency, status, external_ref, metadata)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""",
            tx.txId, tx.tenantId, Timestamp.from(tx.occurredAt), tx.currency,
            tx.status.name, tx.externalRef,
            tx.metadata?.let { objectMapper.writeValueAsString(it) }
        )
    }

    fun insertEntries(entries: List<Entry>) {
        jdbc.batchUpdate(
            """INSERT INTO entries (tenant_id, tx_id, account_id, direction, amount_minor, created_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            entries.map { e ->
                arrayOf(e.tenantId, e.txId, e.accountId, e.direction.name, e.amountMinor, Timestamp.from(e.createdAt))
            }
        )
    }

    fun findById(tenantId: String, txId: String): Transaction? {
        val tx = jdbc.query(
            "SELECT * FROM transactions_min WHERE tx_id = ? AND tenant_id = ?",
            txRowMapper, txId, tenantId
        ).firstOrNull() ?: return null

        val entries = jdbc.query(
            "SELECT * FROM entries WHERE tx_id = ? AND tenant_id = ? ORDER BY id",
            entryRowMapper, txId, tenantId
        )
        return tx.copy(entries = entries)
    }

    fun updateStatus(tenantId: String, txId: String, status: TransactionStatus): Boolean {
        val updated = jdbc.update(
            "UPDATE transactions_min SET status = ? WHERE tx_id = ? AND tenant_id = ?",
            status.name, txId, tenantId
        )
        return updated > 0
    }

    fun findEntriesByTxId(tenantId: String, txId: String): List<Entry> {
        return jdbc.query(
            "SELECT * FROM entries WHERE tx_id = ? AND tenant_id = ? ORDER BY id",
            entryRowMapper, txId, tenantId
        )
    }
}
