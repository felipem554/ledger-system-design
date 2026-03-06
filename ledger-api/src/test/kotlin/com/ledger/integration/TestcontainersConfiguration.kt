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
 * Containers are shared across all integration tests (started once via
 * companion object lazy init, reused for every Spring context reload).
 */
class TestcontainersInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {

    override fun initialize(ctx: ConfigurableApplicationContext) {
        Startables.deepStart(postgres, mongo, kafka).get()

        TestPropertyValues.of(
            "spring.datasource.url=${postgres.jdbcUrl}",
            "spring.datasource.username=${postgres.username}",
            "spring.datasource.password=${postgres.password}",
            "spring.data.mongodb.uri=${mongo.replicaSetUrl}",
            "spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
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
