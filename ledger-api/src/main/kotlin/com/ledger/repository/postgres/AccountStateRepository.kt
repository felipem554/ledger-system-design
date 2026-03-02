package com.ledger.repository.postgres

import com.ledger.domain.model.AccountState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class AccountStateRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        AccountState(
            tenantId = rs.getString("tenant_id"),
            accountId = rs.getString("account_id"),
            postedBalanceMinor = rs.getLong("posted_balance_minor"),
            version = rs.getLong("version"),
            lastTxId = rs.getString("last_tx_id"),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    fun findByAccountId(tenantId: String, accountId: String): AccountState? {
        return jdbc.query(
            "SELECT * FROM account_state WHERE tenant_id = ? AND account_id = ?",
            rowMapper, tenantId, accountId
        ).firstOrNull()
    }

    fun upsertWithOptimisticLock(
        tenantId: String,
        accountId: String,
        balanceDelta: Long,
        txId: String,
        expectedVersion: Long?
    ): AccountState {
        if (expectedVersion == null) {
            // Insert new account state
            jdbc.update(
                """INSERT INTO account_state (tenant_id, account_id, posted_balance_minor, version, last_tx_id, updated_at)
                   VALUES (?, ?, ?, 1, ?, now())
                   ON CONFLICT (tenant_id, account_id) DO UPDATE SET
                     posted_balance_minor = account_state.posted_balance_minor + EXCLUDED.posted_balance_minor,
                     version = account_state.version + 1,
                     last_tx_id = EXCLUDED.last_tx_id,
                     updated_at = now()""",
                tenantId, accountId, balanceDelta, txId
            )
        } else {
            val updated = jdbc.update(
                """UPDATE account_state SET
                     posted_balance_minor = posted_balance_minor + ?,
                     version = version + 1,
                     last_tx_id = ?,
                     updated_at = now()
                   WHERE tenant_id = ? AND account_id = ? AND version = ?""",
                balanceDelta, txId, tenantId, accountId, expectedVersion
            )
            if (updated == 0) {
                throw org.springframework.dao.OptimisticLockingFailureException(
                    "Concurrency conflict on account $accountId version $expectedVersion"
                )
            }
        }
        return findByAccountId(tenantId, accountId)!!
    }

    fun updateBalance(tenantId: String, accountId: String, delta: Long, txId: String) {
        jdbc.update(
            """INSERT INTO account_state (tenant_id, account_id, posted_balance_minor, version, last_tx_id, updated_at)
               VALUES (?, ?, ?, 1, ?, now())
               ON CONFLICT (tenant_id, account_id) DO UPDATE SET
                 posted_balance_minor = account_state.posted_balance_minor + ?,
                 version = account_state.version + 1,
                 last_tx_id = ?,
                 updated_at = now()""",
            tenantId, accountId, delta, txId, delta, txId
        )
    }
}
