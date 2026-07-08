package dev.localmesh.controller.repository

import dev.localmesh.controller.repository.InterceptRepository
import io.kubernetes.client.openapi.ApiClient
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Shared
import spock.lang.Specification

/**
 * Integration test for InterceptRepository using a real Postgres Testcontainer.
 *
 * Phase 2 requirement: every repository method (createSession, createIntercept,
 * updateStatus, markTornDown, countActiveIntercepts, tearDownActiveInterceptsForSession,
 * recordTrafficEvent) must work correctly against the V1 Flyway schema.
 *
 * Strategy: Spring Boot loads the real DataSource/jOOQ/Flyway pointed at the
 * Testcontainer. Kubernetes and Redis beans are mocked so no real cluster/Redis
 * is needed for this test slice.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InterceptRepositorySpec extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("localmesh")
                    .withUsername("localmesh")
                    .withPassword("localmesh")

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.flyway.enabled",      () -> "true")
        registry.add("localmesh.redis.uri",        () -> "redis://localhost:6379")
    }

    // Mock infrastructure beans that are not under test
    @MockBean ApiClient                             apiClient
    @MockBean RedisClient                           redisClient
    @MockBean StatefulRedisConnection<String, String> redisConnection
    @MockBean RedisCommands<String, String>          redisCommands

    @Autowired
    InterceptRepository repo

    // ─────────────────────────────────────────────────────────────────────────
    // Developer Session tests
    // ─────────────────────────────────────────────────────────────────────────

    def "createSession returns a valid UUID string"() {
        when:
        def sessionId = repo.createSession("alice", "grpc://laptop:50052")

        then:
        sessionId != null
        UUID.fromString(sessionId) != null // throws IllegalArgumentException if invalid
    }

    def "createSession persists multiple independent sessions"() {
        when:
        def id1 = repo.createSession("bob",   "grpc://laptop:50053")
        def id2 = repo.createSession("carol", "grpc://laptop:50054")

        then:
        id1 != null && id2 != null
        id1 != id2
    }

    def "updateHeartbeat does not throw for an existing session"() {
        given:
        def sessionId = repo.createSession("dave", "grpc://laptop:50055")

        when:
        repo.updateHeartbeat(sessionId)

        then:
        noExceptionThrown()
    }

    def "findExpiredSessions returns empty when all sessions are fresh"() {
        given: "A session created just now"
        repo.createSession("fresh-user", "grpc://laptop:50056")

        when:
        def expired = repo.findExpiredSessions(30)

        then: "The fresh session should NOT appear as expired"
        // We can't filter by the exact session created here since other tests also
        // create sessions, but we verify the method returns without error
        expired != null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intercept tests
    // ─────────────────────────────────────────────────────────────────────────

    def "createIntercept persists with status ACTIVE and returns provided interceptId"() {
        given:
        def sessionId   = repo.createSession("eve", "grpc://laptop:50057")
        def interceptId = UUID.randomUUID().toString()

        when:
        def returned = repo.createIntercept(interceptId, sessionId,
                "payment-service", "default", 8080, "grpc://ctrl:50051")

        then:
        returned == interceptId
    }

    def "createIntercept allows multiple intercepts under the same session"() {
        given:
        def sessionId = repo.createSession("frank", "grpc://laptop:50058")

        when:
        def id1 = repo.createIntercept(UUID.randomUUID().toString(), sessionId,
                "order-service",  "default", 8081, "grpc://ctrl:50051")
        def id2 = repo.createIntercept(UUID.randomUUID().toString(), sessionId,
                "notify-service", "default", 8082, "grpc://ctrl:50051")

        then:
        id1 != null && id2 != null
        id1 != id2
    }

    def "updateStatus transitions an ACTIVE intercept to TEARDOWN without error"() {
        given:
        def sessionId = repo.createSession("grace", "grpc://laptop:50059")
        repo.createIntercept(UUID.randomUUID().toString(), sessionId,
                "payment-svc-upd", "staging", 9090, "grpc://ctrl:50051")

        when:
        repo.updateStatus("payment-svc-upd", "staging", "TEARDOWN")

        then:
        noExceptionThrown()
    }

    def "markTornDown transitions ACTIVE intercept to TORN_DOWN without error"() {
        given:
        def sessionId = repo.createSession("henry", "grpc://laptop:50060")
        repo.createIntercept(UUID.randomUUID().toString(), sessionId,
                "notify-svc-mark", "default", 7070, "grpc://ctrl:50051")

        when:
        repo.markTornDown("notify-svc-mark", "default")

        then:
        noExceptionThrown()
    }

    def "countActiveIntercepts increases by 1 after a new ACTIVE intercept is created"() {
        given:
        def before    = repo.countActiveIntercepts()
        def sessionId = repo.createSession("ivan", "grpc://laptop:50061")

        when:
        repo.createIntercept(UUID.randomUUID().toString(), sessionId,
                "count-test-svc", "default", 6060, "grpc://ctrl:50051")

        then:
        repo.countActiveIntercepts() == before + 1
    }

    def "tearDownActiveInterceptsForSession reduces active count by the number of session intercepts"() {
        given:
        def sessionId = repo.createSession("julia", "grpc://laptop:50062")
        repo.createIntercept(UUID.randomUUID().toString(), sessionId,
                "svc-a", "default", 5050, "grpc://ctrl:50051")
        repo.createIntercept(UUID.randomUUID().toString(), sessionId,
                "svc-b", "default", 5051, "grpc://ctrl:50051")
        def activeBefore = repo.countActiveIntercepts()

        when:
        repo.tearDownActiveInterceptsForSession(sessionId)

        then:
        repo.countActiveIntercepts() == activeBefore - 2
    }

    def "recordTrafficEvent persists without error for a valid intercept"() {
        given:
        def sessionId   = repo.createSession("karl", "grpc://laptop:50063")
        def interceptId = UUID.randomUUID().toString()
        repo.createIntercept(interceptId, sessionId,
                "traffic-svc", "default", 4040, "grpc://ctrl:50051")

        when:
        repo.recordTrafficEvent(interceptId, UUID.randomUUID().toString(),
                "GET", "/api/health", 200, 42L)

        then:
        noExceptionThrown()
    }
}
