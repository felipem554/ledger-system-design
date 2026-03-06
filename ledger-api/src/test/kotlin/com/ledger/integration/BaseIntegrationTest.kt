package com.ledger.integration

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration

/**
 * Base class for integration tests.
 *
 * Uses Testcontainers to spin up Postgres, MongoDB, and Kafka automatically.
 * No manual docker compose needed — just Docker on the host.
 *
 * See TESTING.md for full details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = [TestcontainersInitializer::class])
abstract class BaseIntegrationTest
