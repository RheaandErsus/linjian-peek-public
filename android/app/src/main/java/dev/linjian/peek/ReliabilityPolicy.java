package dev.linjian.peek;

/** Timing and capability decisions, kept independent of Android for regression checks. */
final class ReliabilityPolicy {
    private ReliabilityPolicy() { }

    static long retryDelay(long base, int failures, double jitter) {
        long boundedBase = Math.max(1500L, Math.min(60000L, base));
        long exponential = Math.min(60000L, boundedBase * (1L << Math.max(0, Math.min(6, failures))));
        return Math.min(60000L, Math.max(1500L, (long) (exponential * (0.85 + 0.15 * Math.max(0, Math.min(1, jitter))))));
    }

    static String accessibilityState(Boolean enabled, boolean connected) {
        if (enabled == null) return "unknown";
        if (!enabled) return "disabled";
        return connected ? "connected" : "disconnected";
    }

    static boolean screenshotAvailable(boolean accessibilityReady, int sdk, boolean projectionReady) {
        return projectionReady || (accessibilityReady && sdk >= 30);
    }

    static boolean fresh(long observed, long now, long maxAge) {
        return observed > 0 && now >= observed && now - observed <= maxAge;
    }

    static boolean readOnlyCommand(String action) {
        return "curbox_status".equals(action) || "curbox_query".equals(action) || "curbox_list".equals(action)
                || "get_life_state".equals(action) || "get_screen_nodes".equals(action)
                || "get_lock_state".equals(action) || "list_lockable_apps".equals(action)
                || "wait".equals(action) || "noop".equals(action);
    }
}
