package com.ledger.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables @Scheduled outbox polling only in worker mode.
 * API-only instances don't need the scheduler.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["ledger.mode"], havingValue = "worker", matchIfMissing = true)
class SchedulingConfig
