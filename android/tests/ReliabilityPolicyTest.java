package dev.linjian.peek;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain JVM regression checks; no device, credentials, or network needed. */
public final class ReliabilityPolicyTest {
    private static int checks;
    private static void check(boolean result) { checks++; if (!result) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        check("disabled".equals(ReliabilityPolicy.accessibilityState(false, true)));
        check("disconnected".equals(ReliabilityPolicy.accessibilityState(true, false)));
        check("connected".equals(ReliabilityPolicy.accessibilityState(true, true)));
        check("unknown".equals(ReliabilityPolicy.accessibilityState(null, false)));
        check("unknown".equals(ReliabilityPolicy.accessibilityState(null, true)));
        check(ReliabilityPolicy.screenshotAvailable(false, 30, true));
        check(!ReliabilityPolicy.screenshotAvailable(false, 30, false));
        check(ReliabilityPolicy.screenshotAvailable(true, 30, false));
        check(!ReliabilityPolicy.screenshotAvailable(true, 29, false));
        check(ReliabilityPolicy.screenshotAvailable(false, 23, true));
        check(ReliabilityPolicy.fresh(100, 200, 100));
        check(!ReliabilityPolicy.fresh(100, 201, 100));
        check(!ReliabilityPolicy.fresh(200, 100, 100));
        check(!ReliabilityPolicy.fresh(0, 100, 100));
        long previous = 0;
        for (int failures = 0; failures < 100; failures++) {
            long low = ReliabilityPolicy.retryDelay(1500, failures, 0);
            long high = ReliabilityPolicy.retryDelay(1500, failures, 1);
            check(low >= 1500 && low <= high && high <= 60000 && high >= previous);
            previous = high;
        }
        check(ReliabilityPolicy.retryDelay(Long.MAX_VALUE, Integer.MAX_VALUE, 1) == 60000);
        check(ReliabilityPolicy.retryDelay(-1, -1, -1) == 1500);
        for (String read : new String[]{"curbox_status", "curbox_query", "curbox_list", "get_life_state", "get_screen_nodes", "get_lock_state", "list_lockable_apps", "wait", "noop"}) check(ReliabilityPolicy.readOnlyCommand(read));
        for (String write : new String[]{"curbox_start_focus", "run_sequence", "input_text"}) check(!ReliabilityPolicy.readOnlyCommand(write));

        java.nio.ByteBuffer rows = java.nio.ByteBuffer.allocate(20);
        for (int i = 0; i < 20; i++) rows.put((byte) i);
        rows.flip();
        java.nio.ByteBuffer pixels = RgbaFrame.compact(rows, 2, 2, 4, 12);
        check(rows.position() == 0 && pixels.remaining() == 16);
        check(pixels.get(7) == 7 && pixels.get(8) == 12 && pixels.get(15) == 19);
        rows.position(2);
        java.nio.ByteBuffer spaced = RgbaFrame.compact(rows, 2, 1, 8, 16);
        check(spaced.remaining() == 8 && spaced.get(0) == 2 && spaced.get(4) == 10);
        check(rows.position() == 2);
        try { RgbaFrame.compact(rows, 3, 3, 4, 12); throw new AssertionError("short buffer accepted"); }
        catch (IllegalArgumentException expected) { check(true); }
        try { RgbaFrame.compact(rows, 0, 1, 4, 12); throw new AssertionError("zero width accepted"); }
        catch (IllegalArgumentException expected) { check(true); }
        try { RgbaFrame.compact(rows, 2, 1, 4, 4); throw new AssertionError("short stride accepted"); }
        catch (IllegalArgumentException expected) { check(true); }

        BoundedCalls calls = new BoundedCalls();
        CountDownLatch release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        try {
            calls.call(() -> { writes.incrementAndGet(); release.await(); finished.countDown(); return "OK"; }, 50L);
            throw new AssertionError("expected unknown result timeout");
        } catch (TimeoutException expected) { check(writes.get() == 1); }
        try {
            calls.call(() -> { writes.incrementAndGet(); return "replayed"; }, 50L);
            throw new AssertionError("must not queue another write behind stuck Binder");
        } catch (RejectedExecutionException expected) { check(writes.get() == 1); }
        check(!calls.offer(() -> writes.incrementAndGet()));
        release.countDown();
        check(finished.await(1, TimeUnit.SECONDS));
        check(writes.get() == 1);
        System.out.println("PASS: " + checks + " policy and bounded Binder checks");
    }
}
