package com.ledger.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.api.dto.*
import com.ledger.api.exception.IdempotencyReplayException
import com.ledger.service.TransactionService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/v1/transactions")
class TransactionController(
    private val txService: TransactionService,
    private val objectMapper: ObjectMapper
) {

    @PostMapping
    fun postTransaction(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: TransactionRequest
    ): ResponseEntity<TransactionCreatedResponse> {
        return try {
            val rawBody = objectMapper.writeValueAsString(request)
            val response = txService.postTransaction(tenantId, idempotencyKey, request, rawBody)
            ResponseEntity.status(HttpStatus.CREATED).body(response)
        } catch (e: IdempotencyReplayException) {
            if (e.responseBody != null) {
                val replayed = objectMapper.readValue(e.responseBody, TransactionCreatedResponse::class.java)
                ResponseEntity.status(e.responseCode ?: 201).body(replayed)
            } else {
                ResponseEntity.status(e.responseCode ?: 201).body(
                    TransactionCreatedResponse(e.txId ?: "", tenantId, com.ledger.domain.model.TransactionStatus.POSTED, Instant.now())
                )
            }
        }
    }

    @GetMapping
    fun listTransactions(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @RequestParam(required = false) externalRef: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int
    ): ResponseEntity<PagedResponse<TransactionResponse>> {
        return ResponseEntity.ok(txService.listTransactions(tenantId, externalRef, from, to, cursor, limit))
    }

    @GetMapping("/{txId}")
    fun getTransaction(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable txId: String
    ): ResponseEntity<TransactionResponse> {
        return ResponseEntity.ok(txService.getTransaction(tenantId, txId))
    }

    @PostMapping("/{txId}:reverse")
    fun reverseTransaction(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable txId: String
    ): ResponseEntity<TransactionCreatedResponse> {
        return try {
            val response = txService.reverseTransaction(tenantId, txId, idempotencyKey)
            ResponseEntity.status(HttpStatus.CREATED).body(response)
        } catch (e: IdempotencyReplayException) {
            if (e.responseBody != null) {
                val replayed = objectMapper.readValue(e.responseBody, TransactionCreatedResponse::class.java)
                ResponseEntity.status(e.responseCode ?: 201).body(replayed)
            } else {
                ResponseEntity.status(e.responseCode ?: 201).body(
                    TransactionCreatedResponse(e.txId ?: "", tenantId, com.ledger.domain.model.TransactionStatus.POSTED, Instant.now())
                )
            }
        }
    }

    @PostMapping(":batch")
    fun batchPost(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @Valid @RequestBody request: BatchRequest
    ): ResponseEntity<BatchResponse> {
        return ResponseEntity.ok(txService.postBatch(tenantId, request))
    }
}
