package com.ledger.unit.eventing

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.domain.model.OutboxEvent
import com.ledger.observability.LedgerMetrics
import com.ledger.repository.postgres.OutboxRepository
import com.ledger.eventing.OutboxPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.core.KafkaTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture

class OutboxPublisherTest {

    private lateinit var outboxRepo: OutboxRepository
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    private lateinit var metrics: LedgerMetrics
    private lateinit var publisher: OutboxPublisher

    private val topic = "ledger.transactions.v1"
    private val batchSize = 50
    private val partitionBuckets = 16

    @BeforeEach
    fun setup() {
        outboxRepo = mock()
        kafkaTemplate = mock()
        metrics = mock()
        publisher = OutboxPublisher(
            outboxRepo, kafkaTemplate, ObjectMapper(), metrics,
            topic, batchSize, partitionBuckets
        )
    }

    @Test
    fun `should skip when no unpublished events`() {
        whenever(outboxRepo.fetchUnpublished(batchSize)).thenReturn(emptyList())

        publisher.pollAndPublish()

        verify(kafkaTemplate, never()).send(any(), any(), any<String>())
        verify(outboxRepo, never()).markPublished(any())
    }

    @Test
    fun `should publish events and mark them as published`() {
        val eventId = UUID.randomUUID()
        val payload = """{"txId":"tx-1","tenantId":"t1"}"""
        val event = OutboxEvent(eventId = eventId, tenantId = "t1", eventType = "TransactionPosted", payload = payload)

        whenever(outboxRepo.fetchUnpublished(batchSize)).thenReturn(listOf(event))
        whenever(kafkaTemplate.send(any(), any(), any<String>())).thenReturn(CompletableFuture.completedFuture(null))

        publisher.pollAndPublish()

        verify(kafkaTemplate).send(eq(topic), any(), eq(payload))
        verify(outboxRepo).markPublished(eq(listOf(eventId)))
        verify(metrics).recordOutboxLag(0)
    }

    @Test
    fun `should stop publishing on first Kafka failure`() {
        val event1 = OutboxEvent(
            eventId = UUID.randomUUID(), tenantId = "t1",
            eventType = "TransactionPosted", payload = """{"txId":"tx-1"}"""
        )
        val event2 = OutboxEvent(
            eventId = UUID.randomUUID(), tenantId = "t1",
            eventType = "TransactionPosted", payload = """{"txId":"tx-2"}"""
        )

        whenever(outboxRepo.fetchUnpublished(batchSize)).thenReturn(listOf(event1, event2))
        whenever(kafkaTemplate.send(any(), any(), any<String>()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("Kafka unavailable")))

        publisher.pollAndPublish()

        verify(kafkaTemplate, times(1)).send(any(), any(), any<String>())
        verify(outboxRepo, never()).markPublished(any())
        verify(metrics).recordOutboxLag(2)
    }

    @Test
    fun `should generate deterministic partition key from txId`() {
        val event = OutboxEvent(
            eventId = UUID.randomUUID(), tenantId = "tenant-a",
            eventType = "TransactionPosted", payload = """{"txId":"tx-abc-123"}"""
        )

        whenever(outboxRepo.fetchUnpublished(batchSize)).thenReturn(listOf(event))
        whenever(kafkaTemplate.send(any(), any(), any<String>())).thenReturn(CompletableFuture.completedFuture(null))

        publisher.pollAndPublish()

        val keyCaptor = argumentCaptor<String>()
        verify(kafkaTemplate).send(eq(topic), keyCaptor.capture(), any<String>())
        val key = keyCaptor.firstValue
        assert(key.startsWith("tenant-a:")) { "Key should start with tenantId: got $key" }
        val bucket = key.substringAfter(":").toInt()
        assert(bucket in 0 until partitionBuckets) { "Bucket should be within bounds: got $bucket" }
    }

    @Test
    fun `should use eventId as fallback when txId missing from payload`() {
        val eventId = UUID.randomUUID()
        val event = OutboxEvent(
            eventId = eventId, tenantId = "t1",
            eventType = "TransactionPosted", payload = """{"noTxIdHere":true}"""
        )

        whenever(outboxRepo.fetchUnpublished(batchSize)).thenReturn(listOf(event))
        whenever(kafkaTemplate.send(any(), any(), any<String>())).thenReturn(CompletableFuture.completedFuture(null))

        publisher.pollAndPublish()

        verify(kafkaTemplate).send(eq(topic), any(), any<String>())
        verify(outboxRepo).markPublished(eq(listOf(eventId)))
    }
}
