package com.ledger.config

import com.ledger.repository.mongo.TransactionDocument
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition
import org.springframework.data.mongodb.core.index.IndexOperations
import org.springframework.stereotype.Component
import org.bson.Document

@Component
class MongoIndexInitializer(private val mongoTemplate: MongoTemplate) {

    @EventListener(ApplicationReadyEvent::class)
    fun ensureIndexes() {
        val ops: IndexOperations = mongoTemplate.indexOps(TransactionDocument::class.java)

        ops.ensureIndex(
            CompoundIndexDefinition(Document("tenantId", 1).append("txId", 1)).unique()
        )
        ops.ensureIndex(
            CompoundIndexDefinition(Document("tenantId", 1).append("occurredAt", -1).append("txId", -1))
        )
        ops.ensureIndex(
            CompoundIndexDefinition(Document("tenantId", 1).append("entries.accountId", 1).append("occurredAt", -1))
        )
    }
}
