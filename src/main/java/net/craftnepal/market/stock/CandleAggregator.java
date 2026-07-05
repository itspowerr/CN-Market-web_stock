package net.craftnepal.market.stock;

import net.craftnepal.market.managers.DatabaseManager;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public final class CandleAggregator {

    public static final String[] INTERVALS = {"1m", "5m", "15m", "1h", "1d"};
    public static final long[] INTERVAL_MS = {
            60_000L,          // 1m
            300_000L,         // 5m
            900_000L,         // 15m
            3_600_000L,       // 1h
            86_400_000L       // 1d
    };

    public static class Candle {
        public final String ticker;
        public final String interval;
        public final long openTime;
        public final double open;
        public final double high;
        public final double low;
        public final double close;
        public final int volume;
        public final int tradeCount;

        public Candle(String ticker, String interval, long openTime,
                      double open, double high, double low, double close,
                      int volume, int tradeCount) {
            this.ticker = ticker;
            this.interval = interval;
            this.openTime = openTime;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.tradeCount = tradeCount;
        }
    }

    // ── Aggregation ──────────────────────────────────────────────────────────

    public static void aggregate() {
        long now = System.currentTimeMillis();

        for (int i = 0; i < INTERVALS.length; i++) {
            try {
                aggregateInterval(INTERVALS[i], INTERVAL_MS[i], now);
            } catch (SQLException e) {
                Bukkit.getLogger().severe("[Market] Candle aggregation failed for " + INTERVALS[i] + ": " + e.getMessage());
            }
        }
    }

    private static void aggregateInterval(String interval, long intervalMs, long now) throws SQLException {
        long lastCandleTime = getLastCandleTime(interval);
        long bucketStart = lastCandleTime > 0 ? lastCandleTime + intervalMs : now - intervalMs * 200;

        if (bucketStart >= now) return;

        String sql = "SELECT ticker, price, quantity, timestamp FROM stock_trades WHERE timestamp >= ? ORDER BY timestamp ASC";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, bucketStart);
            try (ResultSet rs = stmt.executeQuery()) {

                Map<String, Map<Long, CandleBuilder>> buckets = new HashMap<>();

                while (rs.next()) {
                    String ticker = rs.getString("ticker");
                    double price = rs.getDouble("price");
                    int qty = rs.getInt("quantity");
                    long ts = rs.getLong("timestamp");

                    long bucketKey = ts - (ts % intervalMs);

                    buckets
                            .computeIfAbsent(ticker, k -> new HashMap<>())
                            .computeIfAbsent(bucketKey, k -> new CandleBuilder())
                            .add(price, qty);
                }

                // Upsert all built candles
                String upsert = "INSERT OR REPLACE INTO stock_candles (ticker, interval, open_time, open, high, low, close, volume, trade_count) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ups = conn.prepareStatement(upsert)) {
                    for (Map.Entry<String, Map<Long, CandleBuilder>> tickerEntry : buckets.entrySet()) {
                        String ticker = tickerEntry.getKey();
                        for (Map.Entry<Long, CandleBuilder> candleEntry : tickerEntry.getValue().entrySet()) {
                            CandleBuilder cb = candleEntry.getValue();
                            ups.setString(1, ticker);
                            ups.setString(2, interval);
                            ups.setLong(3, candleEntry.getKey());
                            ups.setDouble(4, cb.open);
                            ups.setDouble(5, cb.high);
                            ups.setDouble(6, cb.low);
                            ups.setDouble(7, cb.close);
                            ups.setInt(8, cb.volume);
                            ups.setInt(9, cb.tradeCount);
                            ups.addBatch();
                        }
                    }
                    ups.executeBatch();
                }
            }
        }
    }

    private static long getLastCandleTime(String interval) throws SQLException {
        String sql = "SELECT MAX(open_time) FROM stock_candles WHERE interval = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, interval);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0;
    }

    private static class CandleBuilder {
        double open, high, low, close;
        int volume, tradeCount;
        boolean initialized;

        void add(double price, int qty) {
            if (!initialized) {
                open = high = low = close = price;
                initialized = true;
            } else {
                if (price > high) high = price;
                if (price < low) low = price;
                close = price;
            }
            volume += qty;
            tradeCount++;
        }
    }

    // ── Cleanup old candles ──────────────────────────────────────────────────

    public static void cleanup() {
        long now = System.currentTimeMillis();
        // 1m: keep 24h, 5m: keep 7d, 15m: keep 30d, 1h: keep 90d
        long[] retentionMs = {
                86_400_000L,       // 1m: 1 day
                604_800_000L,      // 5m: 7 days
                2_592_000_000L,    // 15m: 30 days
                7_776_000_000L,    // 1h: 90 days
                0L                 // 1d: forever
        };

        for (int i = 0; i < INTERVALS.length; i++) {
            if (retentionMs[i] == 0) continue;
            long cutoff = now - retentionMs[i];
            String sql = "DELETE FROM stock_candles WHERE interval = ? AND open_time < ?";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, INTERVALS[i]);
                stmt.setLong(2, cutoff);
                int deleted = stmt.executeUpdate();
                if (deleted > 0) {
                    Bukkit.getLogger().info("[Market] Cleaned " + deleted + " old " + INTERVALS[i] + " candles.");
                }
            } catch (SQLException e) {
                Bukkit.getLogger().warning("[Market] Candle cleanup failed for " + INTERVALS[i] + ": " + e.getMessage());
            }
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────

    public static List<Candle> getCandles(String ticker, String interval, int limit) {
        List<Candle> result = new ArrayList<>();
        String sql = "SELECT * FROM stock_candles WHERE ticker = ? AND interval = ? ORDER BY open_time DESC LIMIT ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, ticker.toUpperCase());
            stmt.setString(2, interval);
            stmt.setInt(3, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new Candle(
                            rs.getString("ticker"),
                            rs.getString("interval"),
                            rs.getLong("open_time"),
                            rs.getDouble("open"),
                            rs.getDouble("high"),
                            rs.getDouble("low"),
                            rs.getDouble("close"),
                            rs.getInt("volume"),
                            rs.getInt("trade_count")
                    ));
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to query candles: " + e.getMessage());
        }
        Collections.reverse(result);
        return result;
    }
}
