package com.ledger.api.controller

import com.ledger.api.dto.IdempotencyStatusResponse
import com.ledger.api.exception.TransactionNotFoundException
import com.ledger.service.IdempotencyService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/idempotency")
class IdempotencyController(private val idempotencyService: IdempotencyService) {

    @GetMapping("/{key}")
    fun getIdempotencyStatus(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable key: String
    ): ResponseEntity<IdempotencyStatusResponse> {
        val record = idempotencyService.findByKey(tenantId, key)
            ?: throw TransactionNotFoundException("Idempotency key not found: $key")

        return ResponseEntity.ok(
            IdempotencyStatusResponse(
                key = record.key,
                status = record.status,
                txId = record.txId,
                createdAt = record.createdAt
            )
        )
    }
}
