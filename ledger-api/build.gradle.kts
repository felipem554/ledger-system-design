import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
}

group = "com.ledger"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

extra["testcontainersVersion"] = "1.20.4"

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Utilities
    implementation("com.google.guava:guava:33.3.1-jre")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.awaitility:awaitility-kotlin:4.2.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "21"
    }
}

// --- Test tasks -----------------------------------------------------------

// Default: runs unit tests only (fast, no infra needed)
tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active") ?: "test")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }
    description = "Runs unit + integration tests (requires DB/Kafka or testcontainers profile)"
}

tasks.register<Test>("unitTest") {
    useJUnitPlatform()
    description = "Runs unit tests only — no infrastructure required"
    group = "verification"
    filter {
        includeTestsMatching("com.ledger.unit.*")
    }
}

tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        excludeTags("e2e")
    }
    description = "Runs integration tests (requires compose services or testcontainers profile)"
    group = "verification"
    filter {
        includeTestsMatching("com.ledger.integration.*")
    }
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active") ?: "test")
}

tasks.register<Test>("e2eTest") {
    useJUnitPlatform {
        includeTags("e2e")
    }
    description = "Runs E2E smoke tests against a running ledger stack"
    group = "verification"
    systemProperty("spring.profiles.active", "e2e")
}
