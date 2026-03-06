/*
 * Example Testcontainers setup (Kotlin + JUnit5).
 * Place in src/test/kotlin/... in the implementation project.
 */
import org.junit.jupiter.api.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Containers {
  private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
    .withDatabaseName("ledger")
    .withUsername("ledger")
    .withPassword("ledger")

  private val mongo = MongoDBContainer(DockerImageName.parse("mongo:7"))
  private val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))

  @BeforeAll fun start() {
    postgres.start()
    mongo.start()
    kafka.start()
    System.setProperty("DB_URL", postgres.jdbcUrl)
    System.setProperty("DB_USER", postgres.username)
    System.setProperty("DB_PASS", postgres.password)
    System.setProperty("MONGO_URI", mongo.replicaSetUrl)
    System.setProperty("KAFKA_BOOTSTRAP", kafka.bootstrapServers)
  }

  @AfterAll fun stop() {
    kafka.stop()
    mongo.stop()
    postgres.stop()
  }
}
