package com.ledger.integration

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.DockerImageName

/**
 * Starts Postgres, MongoDB, and Kafka via Testcontainers and injects
 * their connection properties into the Spring context.
 *
 * Only activates when the "testcontainers" profile is present.
 * Containers are shared across all tests (started once, reused via lazy init).
 */
class TestcontainersInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {

    override fun initialize(ctx: ConfigurableApplicationContext) {
        if (!ctx.environment.activeProfiles.contains("testcontainers")) {
            return
        }

        val pg = postgres
        val mg = mongo
        val kf = kafka
        Startables.deepStart(pg, mg, kf).get()

        TestPropertyValues.of(
            "spring.datasource.url=${pg.jdbcUrl}",
            "spring.datasource.username=${pg.username}",
            "spring.datasource.password=${pg.password}",
            "spring.data.mongodb.uri=${mg.replicaSetUrl}",
            "spring.kafka.bootstrap-servers=${kf.bootstrapServers}",
        ).applyTo(ctx.environment)
    }

    companion object {
        private val postgres by lazy {
            PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withDatabaseName("ledger")
                .withUsername("ledger")
                .withPassword("ledger")
        }

        private val mongo by lazy {
            MongoDBContainer(DockerImageName.parse("mongo:7"))
        }

        private val kafka by lazy {
            KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
        }
    }
}
