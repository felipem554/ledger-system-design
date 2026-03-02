package com.ledger.eventing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.domain.model.*
import com.ledger.repository.mongo.TransactionProjectionRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class ProjectionConsumer(
    private val projectionRepo: TransactionProjectionRepository,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${ledger.kafka.topic.dlq}") private val dlqTopic: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val maxRetries = 3

    @KafkaListener(
        topics = ["\${ledger.kafka.topic.transactions}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun consume(record: ConsumerRecord<String, String>, ack: Acknowledgment) {
        var attempt = 0
        while (attempt < maxRetries) {
            try {
                processEvent(record.value())
                ack.acknowledge()
                return
            } catch (e: Exception) {
                attempt++
                log.warn("Error processing event (attempt {}/{}): {}", attempt, maxRetries, e.message)
                if (attempt >= maxRetries) {
                    sendToDlq(record, e)
                    ack.acknowledge()
                    return
                }
            }
        }
    }

    private fun processEvent(eventJson: String) {
        val event: JsonNode = objectMapper.readTree(eventJson)
        val eventType = event.get("eventType")?.asText()
        val tenantId = event.get("tenantId")?.asText() ?: return
        val txId = event.get("txId")?.asText() ?: return
        val occurredAt = java.time.Instant.parse(event.get("occurredAt")?.asText())
        val payload = event.get("payload")

        when (eventType) {
            "TransactionPosted" -> {
                val entries = payload.get("entries")?.map { e ->
                    Entry(
                        tenantId = tenantId, txId = txId,
                        accountId = e.get("accountId").asText(),
                        direction = Direction.valueOf(e.get("direction").asText()),
                        amountMinor = e.get("amountMinor").asLong()
                    )
                } ?: emptyList()

                val tx = Transaction(
                    txId = txId, tenantId = tenantId,
                    occurredAt = occurredAt,
                    currency = payload.get("currency")?.asText() ?: "USD",
                    status = TransactionStatus.valueOf(payload.get("status")?.asText() ?: "POSTED"),
                    externalRef = payload.get("externalRef")?.asText(),
                    entries = entries,
                    metadata = payload.get("metadata")?.let {
                        if (it.isNull) null
                        else {
                            @Suppress("UNCHECKED_CAST")
                            objectMapper.convertValue(it, Map::class.java) as Map<String, Any>
                        }
                    }
                )
                projectionRepo.upsert(tx)
                log.debug("Projected transaction: {}", txId)
            }

            "TransactionReversed" -> {
                projectionRepo.updateStatus(tenantId, txId, TransactionStatus.REVERSED)
                log.debug("Updated projection to REVERSED: {}", txId)
            }

            else -> log.warn("Unknown event type: {}", eventType)
        }
    }

    private fun sendToDlq(record: ConsumerRecord<String, String>, error: Exception) {
        try {
            val dlqPayload = objectMapper.writeValueAsString(
                mapOf(
                    "originalTopic" to record.topic(),
                    "originalPartition" to record.partition(),
                    "originalOffset" to record.offset(),
                    "originalKey" to record.key(),
                    "payload" to record.value(),
                    "error" to error.message,
                    "timestamp" to java.time.Instant.now().toString()
                )
            )
            kafkaTemplate.send(dlqTopic, record.key(), dlqPayload)
            log.error("Sent poison message to DLQ: partition={} offset={}", record.partition(), record.offset())
        } catch (e: Exception) {
            log.error("Failed to send to DLQ: {}", e.message, e)
        }
    }
}
