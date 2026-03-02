package com.ledger.repository.postgres

import com.ledger.domain.model.OutboxEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class OutboxRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        OutboxEvent(
            eventId = UUID.fromString(rs.getString("event_id")),
            tenantId = rs.getString("tenant_id"),
            eventType = rs.getString("event_type"),
            payload = rs.getString("payload"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            publishedAt = rs.getTimestamp("published_at")?.toInstant()
        )
    }

    fun insert(event: OutboxEvent) {
        jdbc.update(
            """INSERT INTO outbox (event_id, tenant_id, event_type, payload, created_at)
               VALUES (?::uuid, ?, ?, ?::jsonb, ?)""",
            event.eventId.toString(), event.tenantId, event.eventType,
            event.payload, Timestamp.from(event.createdAt)
        )
    }

    fun fetchUnpublished(batchSize: Int): List<OutboxEvent> {
        return jdbc.query(
            """SELECT * FROM outbox WHERE published_at IS NULL
               ORDER BY created_at ASC LIMIT ?
               FOR UPDATE SKIP LOCKED""",
            rowMapper, batchSize
        )
    }

    fun markPublished(eventIds: List<UUID>) {
        if (eventIds.isEmpty()) return
        val placeholders = eventIds.joinToString(",") { "?::uuid" }
        jdbc.update(
            "UPDATE outbox SET published_at = now() WHERE event_id IN ($placeholders)",
            *eventIds.map { it.toString() }.toTypedArray()
        )
    }
}
