package net.craftnepal.market.stock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.craftnepal.market.Market;
import net.craftnepal.market.managers.DatabaseManager;
import net.craftnepal.market.stock.ApiKeyManager.ApiKeyData;
import net.craftnepal.market.stock.CandleAggregator;
import net.craftnepal.market.stock.StockMarketEngine.*;
import net.craftnepal.market.utils.EconomyUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public final class EmbeddedServer {

    private static EmbeddedServer instance;
    private HttpServer server;
    private int port;

    private EmbeddedServer() {}

    public static EmbeddedServer getInstance() {
        if (instance == null) instance = new EmbeddedServer();
        return instance;
    }

    public void start() {
        port = Market.getMainConfig().getInt("stock-api.port", 8080);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newCachedThreadPool());

            // Auth
            server.createContext("/api/v1/auth/step1", new AuthStep1Handler());
            server.createContext("/api/v1/auth/step2", new AuthStep2Handler());
            server.createContext("/api/v1/auth/logout", new AuthLogoutHandler());
            server.createContext("/api/v1/auth/status", new AuthStatusHandler());
            server.createContext("/api/v1/balance", new BalanceHandler());

            // Market data
            server.createContext("/api/v1/tickers", new TickersHandler());
            server.createContext("/api/v1/ticker/", new TickerHandler());
            server.createContext("/api/v1/orderbook/", new OrderBookHandler());
            server.createContext("/api/v1/trades/", new RecentTradesHandler());
            server.createContext("/api/v1/candles/", new CandlesHandler());

            // Trading
            server.createContext("/api/v1/orders", new OrdersHandler());
            server.createContext("/api/v1/order/", new OrderHandler());
            server.createContext("/api/v1/portfolio", new PortfolioHandler());

            server.start();
            Bukkit.getLogger().info("[Market] Stock Market API server started on port " + port);
        } catch (IOException e) {
            Bukkit.getLogger().severe("[Market] Failed to start HTTP server on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(2);
            Bukkit.getLogger().info("[Market] Stock Market API server stopped.");
        }
    }

    // ── CORS / OPTIONS Preflight ─────────────────────────────────────────────

    private static void handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization, Cookie");
        exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
        }
    }

    // ── Auth Step 1: Submit API Key ──────────────────────────────────────────

    private static class AuthStep1Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed")); return;
            }
            if (!checkRateLimit(exchange, "rl:" + getClientIp(exchange))) return;

            Map<String, String> body = parseForm(exchange);
            String apiKey = body.get("key");
            if (apiKey == null || !apiKey.startsWith(ApiKeyManager.PREFIX)) {
                sendJson(exchange, 400, error("Invalid API key format")); return;
            }

            String uuid = resolveUuidFromKey(apiKey);
            if (uuid == null) {
                sendJson(exchange, 401, error("Invalid API key. Generate one with /market apikey generate")); return;
            }

            ApiKeyData data = ApiKeyManager.loadApiKey(uuid);
            if (data == null) { sendJson(exchange, 401, error("API key not found")); return; }
            if (data.isExpired()) { sendJson(exchange, 401, error("API key expired. Renew with /market apikey renew")); return; }
            if (data.isLocked()) {
                long remaining = (data.lockedUntil - System.currentTimeMillis()) / 1000;
                sendJson(exchange, 429, error("Account locked. Try again in " + remaining + " seconds")); return;
            }
            if (!ApiKeyManager.verifyKey(apiKey, data.keyHash)) {
                sendJson(exchange, 401, error("Invalid API key")); return;
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("step", 2);
            resp.put("uuid", uuid);

            if (!data.totpEnabled) {
                String totpSecret = TwoFactorAuth.generateSecret();
                String[] backupCodes = TwoFactorAuth.generateBackupCodes();
                String qrUrl = TwoFactorAuth.getQRCodeURL(getPlayerName(uuid), totpSecret);
                resp.put("totp_setup_needed", true);
                resp.put("totp_secret", totpSecret);
                resp.put("qr_code_url", qrUrl);
                resp.put("backup_codes", String.join(", ", backupCodes));
                ApiKeyManager.setupTOTP(uuid, totpSecret, backupCodes);
            } else {
                resp.put("totp_setup_needed", false);
            }

            resp.put("requires_totp", true);
            sendJson(exchange, 200, resp);
        }
    }

    // ── Auth Step 2: Verify TOTP → Create Session ───────────────────────────

    private static class AuthStep2Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed")); return;
            }
            if (!checkRateLimit(exchange, "rl:" + getClientIp(exchange))) return;

            Map<String, String> body = parseForm(exchange);
            String uuid = body.get("uuid");
            String code = body.get("code");
            if (uuid == null || code == null) {
                sendJson(exchange, 400, error("Missing uuid or code")); return;
            }

            ApiKeyData data = ApiKeyManager.loadApiKey(uuid);
            if (data == null || data.isExpired()) { sendJson(exchange, 401, error("Invalid or expired API key")); return; }
            if (data.isLocked()) { sendJson(exchange, 429, error("Account locked")); return; }

            boolean verified = false;
            if (data.totpEnabled) {
                verified = TwoFactorAuth.verifyTOTP(data.totpSecret, code);
                if (!verified) verified = ApiKeyManager.verifyBackupCode(uuid, code);
            } else {
                if ("SETUP_COMPLETE".equals(code)) verified = true;
            }

            if (!verified) { sendJson(exchange, 401, error("Invalid verification code")); return; }

            String sessionToken = ApiKeyManager.createSession(uuid);
            if (sessionToken == null) { sendJson(exchange, 500, error("Failed to create session")); return; }

            String playerName = getPlayerName(uuid);
            long expiresAt = System.currentTimeMillis() + (24L * 60L * 60L * 1000L);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("session_token", sessionToken);
            resp.put("player_name", playerName != null ? playerName : uuid);
            resp.put("uuid", uuid);
            resp.put("expires_at", expiresAt);

            setSessionCookie(exchange, sessionToken, 86400);
            sendJson(exchange, 200, resp);
            Bukkit.getLogger().info("[Market] Player " + playerName + " (" + uuid + ") logged in via web.");
        }
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    private static class AuthLogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!checkRateLimit(exchange, "rl:" + getClientIp(exchange))) return;
            String uuid = getUuidFromSession(exchange);
            if (uuid != null) ApiKeyManager.destroySession(uuid);
            setSessionCookie(exchange, "", 0);
            sendJson(exchange, 200, Map.of("success", true));
        }
    }

    // ── Session Status ───────────────────────────────────────────────────────

    private static class AuthStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            String uuid = getUuidFromSession(exchange);
            if (uuid == null) { sendJson(exchange, 401, error("No active session")); return; }
            if (!checkRateLimit(exchange, "rl:" + uuid)) return;

            ApiKeyData data = ApiKeyManager.loadApiKey(uuid);
            if (data == null || data.isExpired()) {
                ApiKeyManager.destroySession(uuid);
                sendJson(exchange, 401, error("Session expired")); return;
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("authenticated", true);
            resp.put("player_name", getPlayerName(uuid));
            resp.put("uuid", uuid);
            resp.put("balance_raw", EconomyUtils.getBalance(UUID.fromString(uuid)));
            resp.put("session_expires_at", data.sessionExpiresAt);
            resp.put("key_expires_at", data.expiresAt);
            sendJson(exchange, 200, resp);
        }
    }

    // ── Balance ──────────────────────────────────────────────────────────────

    private static class BalanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            String uuid = getUuidFromSession(exchange);
            if (uuid == null) { sendJson(exchange, 401, error("No active session")); return; }
            if (!checkRateLimit(exchange, "rl:" + uuid)) return;

            double balance = EconomyUtils.getBalance(UUID.fromString(uuid));
            Map<String, Object> resp = new HashMap<>();
            resp.put("uuid", uuid);
            resp.put("player_name", getPlayerName(uuid));
            resp.put("balance", EconomyUtils.format(balance));
            resp.put("balance_raw", balance);
            sendJson(exchange, 200, resp);
        }
    }

    // ── Tickers (all) ────────────────────────────────────────────────────────

    private static class TickersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed")); return;
            }
            if (!checkRateLimit(exchange, "rl:" + getClientIp(exchange))) return;

            List<TickerSnapshot> snapshots = StockMarketEngine.getAllTickers();
            List<Map<String, Object>> list = new ArrayList<>();
            for (TickerSnapshot s : snapshots) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ticker", s.ticker);
                m.put("last_price", s.lastPrice);
                m.put("bid", s.bidPrice);
                m.put("ask", s.askPrice);
                m.put("spread", s.spread);
                m.put("change_24h_pct", Math.round(s.change24h * 100.0) / 100.0);
                m.put("volume_24h", s.volume24h);
                m.put("high_24h", s.high24h);
                m.put("low_24h", s.low24h);
                list.add(m);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("tickers", list);
            resp.put("count", list.size());
            resp.put("timestamp", System.currentTimeMillis());
            sendJson(exchange, 200, resp);
        }
    }

    // ── Single Ticker ────────────────────────────────────────────────────────

    private static class TickerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed")); return;
            }
            if (!checkRateLimit(exchange, "rl:" + getClientIp(exchange))) return;

            String path = exchange.getRequestURI().getPath();
            String ticker = path.substring("/api/v1/ticker/".length()).toUpperCase();
            if (ticker.isEmpty()) { sendJson(exchange, 400, error("Missing ticker")); return; }

            TickerSnapshot s = StockMarketEngine.getTickerSnapshot(ticker);
            if (s.lastPrice <= 0 && s.volume24h == 0) {
                sendJson(exchange, 404, error("Ticker not found: " + ticker)); return;
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ticker", s.ticker);
            resp.put("last_price", s.lastPrice);
            resp.put("bid", s.bidPrice);
            resp.put("ask", s.askPrice);
            resp.put("spread", s.spread);
            resp.put("change_24h_pct", Math.round(s.change24h * 100.0) / 100.0);
            resp.put("volume_24h", s.volume24h);
            resp.put("high_24h", s.high24h);
            resp.put("low_24h", s.low24h);
            resp.put("timestamp", System.currentTimeMillis());
            sendJson(exchange, 200, resp);
        }
    }

    // ── Order Book ───────────────────────────────────────────────────────────

    private static class OrderBookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed")); return;
            }
            if (!checkRateLimit(exchange, "rl:" + getClientIp(exchange))) return;

            String path = exchange.getRequestURI().getPath();
            String remaining = path.substring("/api/v1/orderbook/".length());
            String[] parts = remaining.split("/", 2);
            String ticker = parts[0].toUpperCase();
            if (ticker.isEmpty()) { sendJson(exchange, 400, error("Missing ticker")); return; }

            int depth = 20;
            if (parts.length > 1) {
                try { depth = Math.min(50, Math.max(1, Integer.parseInt(parts[1]))); }
                catch (NumberFormatException ignored) {}
            }

            OrderBookSnapshot book = StockMarketEngine.getOrderBook(ticker, depth);
            List<Map<String, Object>> bids = new ArrayList<>();
            List<Map<String, Object>> asks = new ArrayList<>();

            for (OrderBookSnapshot.Level l : book.bids) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("price", l.price); m.put("quantity", l.quantity); m.put("orders", l.orderCount);
                bids.add(m);
            }
            for (OrderBookSnapshot.Level l : book.asks) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("price", l.price); m.put("quantity", l.quantity); m.put("orders", l.orderCount);
                asks.add(m);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ticker", ticker);
            resp.put("bids", bids);
            resp.put("asks", asks);
            resp.put("timestamp", System.currentTimeMillis());
            sendJson(exchange, 200, resp);
        }
    }

    // ── Recent Trades ────────────────────────────────────────────────────────

    private static class RecentTradesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed")); return;
            }
            if (!checkRateLimit(exchange, "rl:" + getClientIp(exchange))) return;

            String path = exchange.getRequestURI().getPath();
            String remaining = path.substring("/api/v1/trades/".length());
            String[] parts = remaining.split("/", 2);
            String ticker = parts[0].toUpperCase();
            if (ticker.isEmpty()) { sendJson(exchange, 400, error("Missing ticker")); return; }

            int limit = 50;
            if (parts.length > 1) {
                try { limit = Math.min(200, Math.max(1, Integer.parseInt(parts[1]))); }
                catch (NumberFormatException ignored) {}
            }

            List<Trade> trades = StockMarketEngine.getRecentTrades(ticker, limit);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Trade t : trades) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id); m.put("price", t.price); m.put("quantity", t.quantity);
                m.put("buyer", t.buyerUuid); m.put("seller", t.sellerUuid);
                m.put("timestamp", t.timestamp);
                list.add(m);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ticker", ticker);
            resp.put("trades", list);
            resp.put("count", list.size());
            sendJson(exchange, 200, resp);
        }
    }

    // ── Candles (OHLCV) ──────────────────────────────────────────────────────

    private static class CandlesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed")); return;
            }
            if (!checkRateLimit(exchange, "rl:" + getClientIp(exchange))) return;

            String path = exchange.getRequestURI().getPath();
            String remaining = path.substring("/api/v1/candles/".length());
            String[] parts = remaining.split("/", 3);

            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                sendJson(exchange, 400, error("Usage: /api/v1/candles/<ticker>/<interval>[/<limit>]"));
                return;
            }

            String ticker = parts[0].toUpperCase();
            String interval = parts[1];
            int limit = 100;
            if (parts.length > 2) {
                try { limit = Math.min(500, Math.max(1, Integer.parseInt(parts[2]))); }
                catch (NumberFormatException ignored) {}
            }

            // Validate interval
            boolean validInterval = false;
            for (String iv : CandleAggregator.INTERVALS) {
                if (iv.equals(interval)) { validInterval = true; break; }
            }
            if (!validInterval) {
                sendJson(exchange, 400, error("Invalid interval. Use: 1m, 5m, 15m, 1h, 1d"));
                return;
            }

            List<CandleAggregator.Candle> candles = CandleAggregator.getCandles(ticker, interval, limit);
            List<Map<String, Object>> list = new ArrayList<>();
            for (CandleAggregator.Candle c : candles) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("openTime", c.openTime);
                m.put("open", c.open);
                m.put("high", c.high);
                m.put("low", c.low);
                m.put("close", c.close);
                m.put("volume", c.volume);
                m.put("tradeCount", c.tradeCount);
                list.add(m);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ticker", ticker);
            resp.put("interval", interval);
            resp.put("candles", list);
            resp.put("count", list.size());
            sendJson(exchange, 200, resp);
        }
    }

    // ── Orders (place / list) ────────────────────────────────────────────────

    private static class OrdersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;

            String uuid = getUuidFromSession(exchange);
            if (uuid == null) { sendJson(exchange, 401, error("No active session")); return; }
            if (!checkRateLimit(exchange, "rl:" + uuid)) return;

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                List<Order> orders = StockMarketEngine.getOpenOrders(uuid);
                List<Map<String, Object>> list = new ArrayList<>();
                for (Order o : orders) {
                    list.add(orderToMap(o));
                }
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("orders", list);
                resp.put("count", list.size());
                sendJson(exchange, 200, resp);

            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> body = parseForm(exchange);
                String ticker = body.get("ticker");
                String side = body.get("side");
                String type = body.get("type");
                String priceStr = body.get("price");
                String qtyStr = body.get("quantity");

                if (ticker == null || side == null || type == null || qtyStr == null) {
                    sendJson(exchange, 400, error("Missing required fields: ticker, side, type, quantity")); return;
                }

                int quantity;
                try { quantity = Integer.parseInt(qtyStr); if (quantity <= 0) throw new NumberFormatException(); }
                catch (NumberFormatException e) { sendJson(exchange, 400, error("Invalid quantity")); return; }

                double price = 0;
                if (priceStr != null && !priceStr.isEmpty()) {
                    try { price = Double.parseDouble(priceStr); if (price < 0) throw new NumberFormatException(); }
                    catch (NumberFormatException e) { sendJson(exchange, 400, error("Invalid price")); return; }
                }

                OrderType ot;
                try { ot = OrderType.valueOf(type.toUpperCase()); }
                catch (IllegalArgumentException e) { sendJson(exchange, 400, error("Invalid type: MARKET, LIMIT, STOP_LOSS, TAKE_PROFIT")); return; }

                OrderSide os;
                try { os = OrderSide.valueOf(side.toUpperCase()); }
                catch (IllegalArgumentException e) { sendJson(exchange, 400, error("Invalid side: BUY or SELL")); return; }

                OrderResult result = StockMarketEngine.placeOrder(uuid, ticker, ot, os, price, quantity);
                if (!result.success) {
                    sendJson(exchange, 400, error(result.error)); return;
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("order", orderToMap(result.order));
                List<Map<String, Object>> tradeList = new ArrayList<>();
                for (Trade t : result.trades) {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("id", t.id); tm.put("price", t.price); tm.put("quantity", t.quantity);
                    tradeList.add(tm);
                }
                resp.put("trades", tradeList);
                resp.put("filled_count", result.trades.size());
                sendJson(exchange, 200, resp);

            } else {
                sendJson(exchange, 405, error("Method not allowed"));
            }
        }
    }

    // ── Single Order (cancel) ────────────────────────────────────────────────

    private static class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;

            String path = exchange.getRequestURI().getPath();
            String orderId = path.substring("/api/v1/order/".length());
            if (orderId.isEmpty()) { sendJson(exchange, 400, error("Missing order ID")); return; }

            String uuid = getUuidFromSession(exchange);
            if (uuid == null) { sendJson(exchange, 401, error("No active session")); return; }
            if (!checkRateLimit(exchange, "rl:" + uuid)) return;

            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                boolean cancelled = StockMarketEngine.cancelOrder(orderId, uuid);
                if (cancelled) {
                    sendJson(exchange, 200, Map.of("success", true, "message", "Order cancelled"));
                } else {
                    sendJson(exchange, 404, error("Order not found or already filled/cancelled"));
                }
            } else {
                sendJson(exchange, 405, error("Use DELETE to cancel"));
            }
        }
    }

    // ── Portfolio ────────────────────────────────────────────────────────────

    private static class PortfolioHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed")); return;
            }

            String uuid = getUuidFromSession(exchange);
            if (uuid == null) { sendJson(exchange, 401, error("No active session")); return; }
            if (!checkRateLimit(exchange, "rl:" + uuid)) return;

            Map<String, Object> portfolio = StockMarketEngine.getPortfolio(uuid);
            sendJson(exchange, 200, portfolio);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String getClientIp(HttpExchange exchange) {
        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static boolean checkRateLimit(HttpExchange exchange, String key) throws IOException {
        if (!RateLimiter.allow(key)) {
            sendJson(exchange, 429, error("Too many requests. Try again later."));
            return false;
        }
        return true;
    }

    private static String resolveUuidFromKey(String plainKey) {
        List<String> allUuids = DatabaseManager.getAllPlayerUuidsWithKeys();
        for (String uuid : allUuids) {
            ApiKeyData data = ApiKeyManager.loadApiKey(uuid);
            if (data != null && ApiKeyManager.verifyKey(plainKey, data.keyHash)) return uuid;
        }
        return null;
    }

    private static String getUuidFromSession(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        String sessionToken = extractCookie(cookie, "market_session");
        if (sessionToken == null || sessionToken.isEmpty()) return null;
        ApiKeyData data = ApiKeyManager.loadApiKeyBySession(sessionToken);
        return data != null ? data.uuid : null;
    }

    private static String getPlayerName(String uuid) {
        Player player = Bukkit.getPlayer(UUID.fromString(uuid));
        if (player != null) return player.getName();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
        return offline.getName();
    }

    private static void setSessionCookie(HttpExchange exchange, String token, int maxAgeSeconds) {
        exchange.getResponseHeaders().add("Set-Cookie",
                "market_session=" + token
                        + "; Path=/; HttpOnly; SameSite=Strict"
                        + "; Max-Age=" + maxAgeSeconds);
    }

    private static String extractCookie(String cookieHeader, String name) {
        for (String c : cookieHeader.split(";")) {
            c = c.trim();
            if (c.startsWith(name + "=")) return c.substring((name + "=").length());
        }
        return null;
    }

    private static Map<String, Object> orderToMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id); m.put("ticker", o.ticker);
        m.put("type", o.type.name()); m.put("side", o.side.name());
        m.put("price", o.price); m.put("quantity", o.quantity);
        m.put("filled", o.filled); m.put("remaining", o.remaining());
        m.put("status", o.status.name()); m.put("created_at", o.createdAt);
        return m;
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        Map<String, String> result = new HashMap<>();
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String query = exchange.getRequestURI().getQuery();
            if (query != null) parseQueryString(query, result);
            return result;
        }

        byte[] buf = exchange.getRequestBody().readAllBytes();
        String body = new String(buf, StandardCharsets.UTF_8);
        if (body.isEmpty()) return result;

        if (contentType != null && contentType.contains("application/json")) {
            parseJson(body, result);
        } else {
            parseQueryString(body, result);
        }
        return result;
    }

    private static void parseQueryString(String qs, Map<String, String> out) {
        for (String pair : qs.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
    }

    private static void parseJson(String json, Map<String, String> out) {
        try {
            json = json.trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1);
                boolean inString = false;
                StringBuilder key = new StringBuilder();
                StringBuilder value = new StringBuilder();
                boolean onValue = false;
                for (int i = 0; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                        inString = !inString;
                        if (!inString && !onValue) {
                        } else if (!inString) {
                            onValue = false;
                            out.put(key.toString().trim(), value.toString().trim());
                            key.setLength(0); value.setLength(0);
                        }
                        continue;
                    }
                    if (!inString) {
                        if (c == ':') { onValue = true; continue; }
                        if (c == ',' || c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
                    }
                    if (!onValue) key.append(c);
                    else value.append(c);
                }
                if (key.length() > 0) out.put(key.toString().trim(), value.toString().trim());
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = toJson(data).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(e.getKey().toString())).append("\":");
                sb.append(toJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(item));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", msg);
        m.put("success", false);
        return m;
    }
}
