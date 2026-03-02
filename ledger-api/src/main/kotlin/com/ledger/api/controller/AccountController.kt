package com.ledger.api.controller

import com.ledger.api.dto.*
import com.ledger.domain.model.AccountStatus
import com.ledger.domain.model.AccountType
import com.ledger.service.AccountService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/accounts")
class AccountController(private val accountService: AccountService) {

    @PostMapping
    fun createAccount(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @Valid @RequestBody request: CreateAccountRequest
    ): ResponseEntity<AccountResponse> {
        val response = accountService.createAccount(tenantId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    fun listAccounts(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @RequestParam(required = false) type: AccountType?,
        @RequestParam(required = false) currency: String?,
        @RequestParam(required = false) status: AccountStatus?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int
    ): ResponseEntity<PagedResponse<AccountResponse>> {
        return ResponseEntity.ok(accountService.listAccounts(tenantId, type, currency, status, cursor, limit))
    }

    @GetMapping("/{accountId}")
    fun getAccount(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable accountId: String
    ): ResponseEntity<AccountResponse> {
        return ResponseEntity.ok(accountService.getAccount(tenantId, accountId))
    }

    @PostMapping("/{accountId}:close")
    fun closeAccount(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable accountId: String
    ): ResponseEntity<Map<String, String>> {
        accountService.closeAccount(tenantId, accountId)
        return ResponseEntity.ok(mapOf("status" to "CLOSED"))
    }

    @GetMapping("/{accountId}/balance")
    fun getBalance(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable accountId: String
    ): ResponseEntity<BalanceResponse> {
        return ResponseEntity.ok(accountService.getBalance(tenantId, accountId))
    }
}
