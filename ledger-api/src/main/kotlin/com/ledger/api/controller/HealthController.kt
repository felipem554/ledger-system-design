package com.ledger.api.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
class HealthController(private val dataSource: DataSource) {

    @GetMapping("/healthz")
    fun liveness(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "UP"))
    }

    @GetMapping("/readyz")
    fun readiness(): ResponseEntity<Map<String, String>> {
        return try {
            dataSource.connection.use { it.isValid(2) }
            ResponseEntity.ok(mapOf("status" to "READY"))
        } catch (e: Exception) {
            ResponseEntity.status(503).body(mapOf("status" to "NOT_READY", "reason" to (e.message ?: "unknown")))
        }
    }
}
