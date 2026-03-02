package com.ledger.integration

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class BaseIntegrationTest {

    companion object {
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger")

        val mongo = MongoDBContainer(DockerImageName.parse("mongo:7"))

        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))

        init {
            postgres.start()
            mongo.start()
            kafka.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.mongodb.uri") { mongo.replicaSetUrl }
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }
}
