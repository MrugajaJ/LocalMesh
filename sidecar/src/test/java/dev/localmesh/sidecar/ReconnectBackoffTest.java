package dev.localmesh.sidecar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconnectBackoffTest {

    private final ReconnectPolicy policy = new ReconnectPolicy();

    @Test
    @DisplayName("Attempt 0 returns 1000ms delay")
    void attemptZero() {
        assertEquals(1000L, policy.nextDelayMs(0));
    }

    @Test
    @DisplayName("Attempt 1 returns 2000ms delay")
    void attemptOne() {
        assertEquals(2000L, policy.nextDelayMs(1));
    }

    @Test
    @DisplayName("Attempt 2 returns 4000ms delay")
    void attemptTwo() {
        assertEquals(4000L, policy.nextDelayMs(2));
    }

    @Test
    @DisplayName("Attempt 5+ returns max delay of 30000ms")
    void attemptCap() {
        assertEquals(30000L, policy.nextDelayMs(5));
        assertEquals(30000L, policy.nextDelayMs(6));
        assertEquals(30000L, policy.nextDelayMs(100));
    }

    @Test
    @DisplayName("Legacy conversion returns correct attempts")
    void legacyConversion() {
        assertEquals(0, ReconnectPolicy.attemptFromLegacyDelaySec(1));
        assertEquals(1, ReconnectPolicy.attemptFromLegacyDelaySec(2));
        assertEquals(2, ReconnectPolicy.attemptFromLegacyDelaySec(4));
        assertEquals(3, ReconnectPolicy.attemptFromLegacyDelaySec(8));
        assertEquals(4, ReconnectPolicy.attemptFromLegacyDelaySec(16));
        assertEquals(5, ReconnectPolicy.attemptFromLegacyDelaySec(30)); // 16 < 30 <= 32 so it takes 5 attempts
    }
}
