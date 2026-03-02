package com.ledger.repository.mongo

import com.ledger.domain.model.Direction
import com.ledger.domain.model.Transaction
import com.ledger.domain.model.TransactionStatus
import com.ledger.api.dto.EntryResponse
import com.ledger.api.dto.TransactionResponse
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class TransactionProjectionRepository(private val mongo: MongoTemplate) {

    fun upsert(tx: Transaction) {
        val query = Query(
            Criteria.where("tenantId").`is`(tx.tenantId)
                .and("txId").`is`(tx.txId)
        )
        val update = Update()
            .setOnInsert("tenantId", tx.tenantId)
            .setOnInsert("txId", tx.txId)
            .setOnInsert("occurredAt", tx.occurredAt)
            .setOnInsert("currency", tx.currency)
            .set("status", tx.status.name)
            .setOnInsert("externalRef", tx.externalRef)
            .setOnInsert("entries", tx.entries.map {
                EntryDocument(it.accountId, it.direction.name, it.amountMinor)
            })
            .setOnInsert("metadata", tx.metadata)

        mongo.upsert(query, update, TransactionDocument::class.java)
    }

    fun findByTxId(tenantId: String, txId: String): TransactionResponse? {
        val query = Query(
            Criteria.where("tenantId").`is`(tenantId)
                .and("txId").`is`(txId)
        )
        return mongo.findOne(query, TransactionDocument::class.java)?.toResponse()
    }

    fun findByTenant(
        tenantId: String,
        externalRef: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        cursor: String? = null,
        limit: Int = 100
    ): List<TransactionResponse> {
        val criteria = Criteria.where("tenantId").`is`(tenantId)
        externalRef?.let { criteria.and("externalRef").`is`(it) }

        if (from != null || to != null) {
            val timeCriteria = Criteria.where("occurredAt")
            from?.let { timeCriteria.gte(it) }
            to?.let { timeCriteria.lte(it) }
            criteria.andOperator(timeCriteria)
        }

        cursor?.let { criteria.and("txId").lt(it) }

        val query = Query(criteria)
            .with(Sort.by(Sort.Direction.DESC, "occurredAt", "txId"))
            .limit(limit)

        return mongo.find(query, TransactionDocument::class.java).map { it.toResponse() }
    }

    fun updateStatus(tenantId: String, txId: String, status: TransactionStatus) {
        val query = Query(
            Criteria.where("tenantId").`is`(tenantId)
                .and("txId").`is`(txId)
        )
        val update = Update().set("status", status.name)
        mongo.updateFirst(query, update, TransactionDocument::class.java)
    }

    private fun TransactionDocument.toResponse() = TransactionResponse(
        txId = txId,
        tenantId = tenantId,
        occurredAt = occurredAt,
        currency = currency,
        status = TransactionStatus.valueOf(status),
        externalRef = externalRef,
        entries = entries.map {
            EntryResponse(it.accountId, Direction.valueOf(it.direction), it.amountMinor)
        },
        metadata = metadata
    )
}
