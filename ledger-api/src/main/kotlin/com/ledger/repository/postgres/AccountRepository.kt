package com.ledger.repository.postgres

import com.ledger.domain.model.Account
import com.ledger.domain.model.AccountStatus
import com.ledger.domain.model.AccountType
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class AccountRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper
) {

    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        Account(
            id = rs.getString("id"),
            tenantId = rs.getString("tenant_id"),
            name = rs.getString("name"),
            type = AccountType.valueOf(rs.getString("type")),
            currency = rs.getString("currency"),
            status = AccountStatus.valueOf(rs.getString("status")),
            metadata = rs.getString("metadata")?.let {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(it, Map::class.java) as Map<String, Any>
            },
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    fun insert(account: Account) {
        jdbc.update(
            """INSERT INTO accounts (id, tenant_id, name, type, currency, status, metadata, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
            account.id, account.tenantId, account.name, account.type.name,
            account.currency, account.status.name,
            account.metadata?.let { objectMapper.writeValueAsString(it) },
            Timestamp.from(account.createdAt)
        )
    }

    fun findById(tenantId: String, accountId: String): Account? {
        return jdbc.query(
            "SELECT * FROM accounts WHERE id = ? AND tenant_id = ?",
            rowMapper, accountId, tenantId
        ).firstOrNull()
    }

    fun findByTenant(
        tenantId: String,
        type: AccountType? = null,
        currency: String? = null,
        status: AccountStatus? = null,
        cursor: String? = null,
        limit: Int = 100
    ): List<Account> {
        val conditions = mutableListOf("tenant_id = ?")
        val params = mutableListOf<Any>(tenantId)

        type?.let { conditions.add("type = ?"); params.add(it.name) }
        currency?.let { conditions.add("currency = ?"); params.add(it) }
        status?.let { conditions.add("status = ?"); params.add(it.name) }
        cursor?.let { conditions.add("id > ?"); params.add(it) }

        params.add(limit)

        val sql = "SELECT * FROM accounts WHERE ${conditions.joinToString(" AND ")} ORDER BY id ASC LIMIT ?"
        return jdbc.query(sql, rowMapper, *params.toTypedArray())
    }

    fun closeAccount(tenantId: String, accountId: String): Boolean {
        val updated = jdbc.update(
            "UPDATE accounts SET status = 'CLOSED' WHERE id = ? AND tenant_id = ? AND status = 'OPEN'",
            accountId, tenantId
        )
        return updated > 0
    }
}
