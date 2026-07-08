package dev.localmesh.sidecar;

/**
 * ReconnectPolicy — extracted from SidecarMain.scheduleReconnect()
 *
 * Implements exponential backoff for gRPC tunnel reconnection:
 *   attempt 0 → 1 000 ms
 *   attempt 1 → 2 000 ms
 *   attempt 2 → 4 000 ms
 *   attempt N → min(1000 * 2^N, 30 000 ms)
 *
 * The attempt counter must be reset to 0 on successful connection.
 * This class is stateless — callers manage the attempt number.
 */
public class ReconnectPolicy {

    /** Base delay in milliseconds (1 second). */
    static final long BASE_DELAY_MS = 1_000L;

    /** Maximum delay cap in milliseconds (30 seconds). */
    static final long MAX_DELAY_MS = 30_000L;

    /**
     * Returns the delay in milliseconds for a given reconnect attempt number.
     *
     * @param attemptNumber zero-based attempt index (reset to 0 on success)
     * @return delay in milliseconds, capped at MAX_DELAY_MS
     */
    public long nextDelayMs(int attemptNumber) {
        if (attemptNumber < 0) {
            return BASE_DELAY_MS;
        }
        // Use a shift to avoid overflow on large attempt numbers
        long shift = Math.min(attemptNumber, 30); // 2^30 > Integer.MAX_VALUE, safe cap
        long delay = BASE_DELAY_MS * (1L << shift);
        return Math.min(delay, MAX_DELAY_MS);
    }

    /**
     * Convenience: converts the legacy integer-seconds delay used by SidecarMain
     * into the attempt number for this policy.
     *
     * Legacy code used: nextDelay = Math.min(delaySec * 2, 30) starting from delaySec=1
     * That corresponds to attempt = log2(delaySec).
     * This factory method converts: startingDelaySec=1 → attempt=0, 2→1, 4→2, etc.
     */
    public static int attemptFromLegacyDelaySec(int delaySec) {
        if (delaySec <= 1) return 0;
        int attempt = 0;
        int d = 1;
        while (d < delaySec && attempt < 31) {
            d *= 2;
            attempt++;
        }
        return attempt;
    }
}
