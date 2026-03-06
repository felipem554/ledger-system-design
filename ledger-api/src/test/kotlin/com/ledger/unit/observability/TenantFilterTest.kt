package com.ledger.unit.observability

import com.ledger.observability.TenantFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TenantFilterTest {

    private val filter = TenantFilter()

    @Test
    fun `should extract tenant and trace IDs from headers`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Tenant-Id", "my-tenant")
        request.addHeader("X-Trace-Id", "abc123")
        val response = MockHttpServletResponse()

        var capturedTenant: String? = null
        var capturedTrace: String? = null

        val chain = FilterChain { _, _ ->
            capturedTenant = MDC.get("tenantId")
            capturedTrace = MDC.get("traceId")
        }

        filter.doFilter(request, response, chain)

        assert(capturedTenant == "my-tenant") { "Expected tenantId=my-tenant, got $capturedTenant" }
        assert(capturedTrace == "abc123") { "Expected traceId=abc123, got $capturedTrace" }
        assert(response.getHeader("X-Trace-Id") == "abc123")
    }

    @Test
    fun `should generate trace ID when not provided`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Tenant-Id", "t1")
        val response = MockHttpServletResponse()

        var capturedTrace: String? = null
        val chain = FilterChain { _, _ ->
            capturedTrace = MDC.get("traceId")
        }

        filter.doFilter(request, response, chain)

        assert(capturedTrace != null) { "Trace ID should be generated" }
        assert(capturedTrace!!.length == 16) { "Generated trace ID should be 16 chars, got ${capturedTrace!!.length}" }
        assert(response.getHeader("X-Trace-Id") == capturedTrace)
    }

    @Test
    fun `should clean up MDC after request completes`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Tenant-Id", "cleanup-test")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assert(MDC.get("tenantId") == null) { "tenantId should be cleaned from MDC" }
        assert(MDC.get("traceId") == null) { "traceId should be cleaned from MDC" }
    }

    @Test
    fun `should clean up MDC even when filter chain throws`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Tenant-Id", "error-test")
        val response = MockHttpServletResponse()

        val chain = FilterChain { _, _ ->
            throw RuntimeException("boom")
        }

        try {
            filter.doFilter(request, response, chain)
        } catch (_: RuntimeException) {}

        assert(MDC.get("tenantId") == null)
        assert(MDC.get("traceId") == null)
    }
}
