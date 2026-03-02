package com.ledger.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Component
class LedgerMetrics(private val registry: MeterRegistry) {

    private val postingsCounter: Counter = Counter.builder("ledger_postings_total")
        .description("Total number of ledger postings")
        .register(registry)

    private val postingSuccessCounter: Counter = Counter.builder("ledger_postings_total")
        .tag("result", "success")
        .description("Successful postings")
        .register(registry)

    private val postingConflictCounter: Counter = Counter.builder("ledger_postings_total")
        .tag("result", "concurrency_conflict")
        .description("Concurrency conflict postings")
        .register(registry)

    private val idempotencyHitsCounter: Counter = Counter.builder("ledger_idempotency_hits_total")
        .description("Total idempotency cache hits")
        .register(registry)

    private val postingLatencyTimer: Timer = Timer.builder("ledger_posting_latency_seconds")
        .description("Posting latency")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)

    private val outboxLag = AtomicLong(0)

    init {
        registry.gauge("ledger_outbox_lag_seconds", outboxLag) { it.get().toDouble() }
    }

    fun recordPosting(result: String) {
        postingsCounter.increment()
        when (result) {
            "success" -> postingSuccessCounter.increment()
            "concurrency_conflict" -> postingConflictCounter.increment()
        }
    }

    fun recordPostingLatency(nanos: Long) {
        postingLatencyTimer.record(Duration.ofNanos(nanos))
    }

    fun recordIdempotencyHit() {
        idempotencyHitsCounter.increment()
    }

    fun recordOutboxLag(pendingCount: Int) {
        outboxLag.set(pendingCount.toLong())
    }
}
