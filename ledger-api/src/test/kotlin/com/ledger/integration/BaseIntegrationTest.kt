package com.ledger.integration

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration

/**
 * Base class for integration tests.
 *
 * By default, tests expect infrastructure (Postgres, Mongo, Kafka) to be
 * running externally (e.g. via docker compose). To use Testcontainers instead,
 * activate the "testcontainers" profile:
 *
 *   ./gradlew test -Dspring.profiles.active=test,testcontainers
 *
 * See TESTING.md for full details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = [TestcontainersInitializer::class])
abstract class BaseIntegrationTest
