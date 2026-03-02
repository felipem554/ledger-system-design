package com.ledger.eventing

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.hash.Hashing
import com.ledger.observability.LedgerMetrics
import com.ledger.repository.postgres.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

@Component
class OutboxPublisher(
    private val outboxRepo: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val metrics: LedgerMetrics,
    @Value("\${ledger.kafka.topic.transactions}") private val topic: String,
    @Value("\${ledger.outbox.batch-size}") private val batchSize: Int,
    @Value("\${ledger.kafka.partition-buckets}") private val partitionBuckets: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ledger.outbox.poll-interval-ms}")
    fun pollAndPublish() {
        try {
            val events = outboxRepo.fetchUnpublished(batchSize)
            if (events.isEmpty()) return

            val publishedIds = mutableListOf<java.util.UUID>()

            for (event in events) {
                try {
                    val payload = objectMapper.readTree(event.payload)
                    val txId = payload.get("txId")?.asText() ?: event.eventId.toString()
                    val partitionKey = buildPartitionKey(event.tenantId, txId)

                    kafkaTemplate.send(topic, partitionKey, event.payload).get()
                    publishedIds.add(event.eventId)
                } catch (e: Exception) {
                    log.error("Failed to publish outbox event {}: {}", event.eventId, e.message)
                    break
                }
            }

            if (publishedIds.isNotEmpty()) {
                outboxRepo.markPublished(publishedIds)
                log.debug("Published {} outbox events", publishedIds.size)
            }

            metrics.recordOutboxLag(events.size - publishedIds.size)

        } catch (e: Exception) {
            log.error("Outbox poll error: {}", e.message, e)
        }
    }

    private fun buildPartitionKey(tenantId: String, txId: String): String {
        val bucket = Math.abs(
            Hashing.murmur3_32_fixed().hashString(txId, StandardCharsets.UTF_8).asInt()
        ) % partitionBuckets
        return "$tenantId:$bucket"
    }
}
