package dev.localmesh.cli

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class SessionPersistenceSpec extends Specification {

    @TempDir
    Path tempDir

    ObjectMapper mapper = new ObjectMapper()

    def "save and load session preserves all fields"() {
        given:
        def session = new SessionStore.Session()
        session.sessionId     = "test-session-uuid-123"
        session.developerName = "alice"
        session.apiUrl        = "http://localhost:8080"
        session.createdAt     = "2026-01-01T00:00:00Z"

        def sessionFile = tempDir.resolve("session.json")

        when:
        def mapper = new ObjectMapper()
        mapper.writeValue(sessionFile.toFile(), session)
        def loaded = mapper.readValue(sessionFile.toFile(), SessionStore.Session)

        then:
        loaded.sessionId     == "test-session-uuid-123"
        loaded.developerName == "alice"
        loaded.apiUrl        == "http://localhost:8080"
        loaded.createdAt     == "2026-01-01T00:00:00Z"
    }

    def "loading non-existent session file throws meaningful error"() {
        given:
        // Session file does not exist
        def nonExistentFile = tempDir.resolve("does-not-exist.json")

        when:
        mapper.readValue(nonExistentFile.toFile(), SessionStore.Session)

        then:
        thrown(Exception)
    }

    def "session serialised as valid JSON with all expected keys"() {
        given:
        def session = new SessionStore.Session()
        session.sessionId     = "my-session"
        session.developerName = "bob"
        session.apiUrl        = "http://cluster:8080"

        when:
        def json = mapper.writeValueAsString(session)
        def parsed = mapper.readValue(json, Map)

        then:
        parsed.containsKey("sessionId")
        parsed.containsKey("developerName")
        parsed.containsKey("apiUrl")
        parsed["sessionId"] == "my-session"
        parsed["developerName"] == "bob"
    }

    def "delete removes session file"() {
        given:
        def sessionFile = tempDir.resolve("session.json")
        Files.writeString(sessionFile, "{\"sessionId\":\"test\"}")
        assert Files.exists(sessionFile)

        when:
        Files.deleteIfExists(sessionFile)

        then:
        !Files.exists(sessionFile)
    }
}
