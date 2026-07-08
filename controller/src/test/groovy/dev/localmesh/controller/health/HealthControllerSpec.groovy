package dev.localmesh.controller.health

import dev.localmesh.controller.repository.InterceptRepository
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pure unit test for HealthController — no Spring context, no Docker.
 *
 * HealthController is a thin wrapper: it calls countActiveIntercepts() and
 * wraps the result in a ResponseEntity. A plain Spock Mock is sufficient.
 *
 * Phase 2 requirement: GET /health returns { status:"ok", interceptCount:N, timestamp:<epoch> }
 */
class HealthControllerSpec extends Specification {

    InterceptRepository interceptRepository = Mock()

    @Subject
    HealthController controller = new HealthController(interceptRepository)

    def "health() returns HTTP 200 with status='ok'"() {
        given:
        interceptRepository.countActiveIntercepts() >> 0

        when:
        def response = controller.health()

        then:
        response.statusCode == HttpStatus.OK
        response.body["status"] == "ok"
    }

    def "health() body contains interceptCount from repository"() {
        given:
        interceptRepository.countActiveIntercepts() >> 7

        when:
        def response = controller.health()

        then:
        response.statusCode == HttpStatus.OK
        response.body["interceptCount"] == 7
    }

    def "health() body contains a positive numeric timestamp"() {
        given:
        interceptRepository.countActiveIntercepts() >> 0
        def before = System.currentTimeMillis()

        when:
        def response = controller.health()

        then:
        def ts = response.body["timestamp"] as Long
        ts >= before
    }

    def "health() is data-driven for various intercept counts"() {
        given:
        interceptRepository.countActiveIntercepts() >> count

        when:
        def response = controller.health()

        then:
        response.statusCode == HttpStatus.OK
        response.body["interceptCount"] == count

        where:
        count << [0, 1, 10, 100]
    }

    def "health() body has exactly the required fields: status, interceptCount, timestamp"() {
        given:
        interceptRepository.countActiveIntercepts() >> 3

        when:
        def response = controller.health()

        then:
        def body = response.body
        body.containsKey("status")
        body.containsKey("interceptCount")
        body.containsKey("timestamp")
    }
}
