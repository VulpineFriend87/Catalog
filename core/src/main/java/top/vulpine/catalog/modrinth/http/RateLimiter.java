package top.vulpine.catalog.modrinth.http;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A sliding-window limiter that keeps Catalog inside Modrinth's published request budget.
 *
 * <p>The documented allowance is 300 requests per minute per IP.</p>
 */
public final class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int permits;
    private final Deque<Long> window = new ArrayDeque<>();

    /** Set when the API answers 429. */
    private long blockedUntil;

    public RateLimiter(int permitsPerMinute) {
        this.permits = permitsPerMinute;
    }

    /**
     * Waits until another request may be sent, then records it.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public synchronized void acquire() throws InterruptedException {

        while (true) {

            long now = System.currentTimeMillis();

            if (now < blockedUntil) {
                wait(blockedUntil - now);
                continue;
            }

            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }

            if (window.size() < permits) {
                window.addLast(now);
                return;
            }

            wait(WINDOW_MILLIS - (now - window.peekFirst()));
        }
    }

    /**
     * Applies a back-off requested by the API.
     *
     * @param seconds how long Modrinth asked to wait
     */
    public synchronized void backOff(long seconds) {
        blockedUntil = Math.max(blockedUntil, System.currentTimeMillis() + (seconds * 1000L));
        notifyAll();
    }

}
