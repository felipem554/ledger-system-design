package com.ledger.unit.eventing

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.domain.model.*
import com.ledger.eventing.ProjectionConsumer
import com.ledger.repository.mongo.TransactionProjectionRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant
import java.util.concurrent.CompletableFuture

class ProjectionConsumerTest {

    private lateinit var projectionRepo: TransactionProjectionRepository
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    private lateinit var consumer: ProjectionConsumer
    private lateinit var ack: Acknowledgment
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val dlqTopic = "ledger.outbox.dlq.v1"

    @BeforeEach
    fun setup() {
        projectionRepo = mock()
        kafkaTemplate = mock()
        ack = mock()
        consumer = ProjectionConsumer(projectionRepo, objectMapper, kafkaTemplate, dlqTopic)
    }

    private fun buildEvent(eventType: String, txId: String = "tx-1", tenantId: String = "t1"): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "schemaVersion" to 1,
                "eventType" to eventType,
                "tenantId" to tenantId,
                "txId" to txId,
                "occurredAt" to Instant.now().toString(),
                "payload" to mapOf(
                    "currency" to "EUR",
                    "status" to "POSTED",
                    "externalRef" to null,
                    "entries" to listOf(
                        mapOf("accountId" to "acc-1", "direction" to "DEBIT", "amountMinor" to 1000),
                        mapOf("accountId" to "acc-2", "direction" to "CREDIT", "amountMinor" to 1000)
                    ),
                    "metadata" to null
                )
            )
        )
    }

    private fun record(value: String, key: String = "t1:0"): ConsumerRecord<String, String> {
        return ConsumerRecord("ledger.transactions.v1", 0, 0L, key, value)
    }

    @Test
    fun `should upsert projection on TransactionPosted event`() {
        val event = buildEvent("TransactionPosted")

        consumer.consume(record(event), ack)

        verify(projectionRepo).upsert(argThat<Transaction> { this.txId == "tx-1" && this.currency == "EUR" })
        verify(ack).acknowledge()
    }

    @Test
    fun `should update status on TransactionReversed event`() {
        val event = buildEvent("TransactionReversed", txId = "tx-99")

        consumer.consume(record(event), ack)

        verify(projectionRepo).updateStatus("t1", "tx-99", TransactionStatus.REVERSED)
        verify(ack).acknowledge()
    }

    @Test
    fun `should acknowledge on unknown event type without error`() {
        val event = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "UnknownEvent",
                "tenantId" to "t1",
                "txId" to "tx-1",
                "occurredAt" to Instant.now().toString(),
                "payload" to emptyMap<String, Any>()
            )
        )

        consumer.consume(record(event), ack)

        verify(projectionRepo, never()).upsert(any())
        verify(projectionRepo, never()).updateStatus(any(), any(), any())
        verify(ack).acknowledge()
    }

    @Test
    fun `should send to DLQ after max retries and still acknowledge`() {
        whenever(projectionRepo.upsert(any())).thenThrow(RuntimeException("Mongo down"))
        whenever(kafkaTemplate.send(any(), any(), any<String>())).thenReturn(CompletableFuture.completedFuture(null))

        val event = buildEvent("TransactionPosted")

        consumer.consume(record(event), ack)

        verify(projectionRepo, times(3)).upsert(any())
        verify(kafkaTemplate).send(eq(dlqTopic), any(), any<String>())
        verify(ack).acknowledge()
    }

    @Test
    fun `should succeed on second retry`() {
        val event = buildEvent("TransactionPosted")
        var callCount = 0
        whenever(projectionRepo.upsert(any())).thenAnswer {
            callCount++
            if (callCount == 1) throw RuntimeException("transient failure")
        }

        consumer.consume(record(event), ack)

        verify(projectionRepo, times(2)).upsert(any())
        verify(kafkaTemplate, never()).send(eq(dlqTopic), any(), any<String>())
        verify(ack).acknowledge()
    }
}
