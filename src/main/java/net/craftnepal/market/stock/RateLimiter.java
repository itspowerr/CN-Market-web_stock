package net.craftnepal.market.stock;

import org.bukkit.Bukkit;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {

    private static final ConcurrentHashMap<String, ArrayDeque<Long>> logs = new ConcurrentHashMap<>();

    private static int defaultMaxRequests = 60;
    private static long defaultWindowMs = 60_000L;

    private RateLimiter() {}

    public static void configure(int maxRequests, long windowMs) {
        defaultMaxRequests = maxRequests;
        defaultWindowMs = windowMs;
        Bukkit.getLogger().info("[Market] Rate limiter: " + maxRequests + " req / " + (windowMs / 1000) + "s");
    }

    public static boolean allow(String key) {
        return allow(key, defaultMaxRequests, defaultWindowMs);
    }

    public static boolean allow(String key, int maxRequests, long windowMs) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> log = logs.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (log) {
            while (!log.isEmpty() && log.peekFirst() < now - windowMs) {
                log.pollFirst();
            }
            if (log.size() >= maxRequests) return false;
            log.addLast(now);
            return true;
        }
    }

    public static void cleanup() {
        long now = System.currentTimeMillis();
        logs.forEach((key, log) -> {
            synchronized (log) {
                while (!log.isEmpty() && log.peekFirst() < now - defaultWindowMs * 2) {
                    log.pollFirst();
                }
                if (log.isEmpty()) logs.remove(key, log);
            }
        });
    }
}
