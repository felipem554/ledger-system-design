package com.ledger.repository.mongo

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "transactions")
@CompoundIndexes(
    CompoundIndex(name = "uidx_tenant_tx", def = "{'tenantId': 1, 'txId': 1}", unique = true),
    CompoundIndex(name = "idx_tenant_time", def = "{'tenantId': 1, 'occurredAt': -1, 'txId': -1}"),
    CompoundIndex(name = "idx_tenant_account", def = "{'tenantId': 1, 'entries.accountId': 1, 'occurredAt': -1}")
)
data class TransactionDocument(
    @Id val id: String? = null,
    val tenantId: String,
    val txId: String,
    val occurredAt: Instant,
    val currency: String,
    val status: String,
    val externalRef: String? = null,
    val entries: List<EntryDocument> = emptyList(),
    val metadata: Map<String, Any>? = null
)

data class EntryDocument(
    val accountId: String,
    val direction: String,
    val amountMinor: Long
)
