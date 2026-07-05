package net.craftnepal.market.stock;

import net.craftnepal.market.Market;
import net.craftnepal.market.managers.DatabaseManager;
import net.craftnepal.market.managers.DynamicPriceManager;
import net.craftnepal.market.utils.EconomyUtils;
import org.bukkit.Bukkit;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class StockMarketEngine {

    private static final SecureRandom RANDOM = new SecureRandom();

    // ── Order Types ──────────────────────────────────────────────────────────

    public enum OrderType {
        MARKET, LIMIT, STOP_LOSS, TAKE_PROFIT
    }

    public enum OrderSide {
        BUY, SELL
    }

    public enum OrderStatus {
        OPEN, PARTIALLY_FILLED, FILLED, CANCELLED
    }

    // ── Models ───────────────────────────────────────────────────────────────

    public static class Order {
        public final String id;
        public final String ticker;
        public final String playerUuid;
        public OrderType type;
        public final OrderSide side;
        public final double price;
        public final int quantity;
        public int filled;
        public OrderStatus status;
        public final double stopPrice;
        public final long createdAt;

        public Order(String id, String ticker, String playerUuid, OrderType type, OrderSide side,
                     double price, int quantity, int filled, OrderStatus status,
                     double stopPrice, long createdAt) {
            this.id = id; this.ticker = ticker; this.playerUuid = playerUuid;
            this.type = type; this.side = side; this.price = price;
            this.quantity = quantity; this.filled = filled; this.status = status;
            this.stopPrice = stopPrice; this.createdAt = createdAt;
        }

        public int remaining() { return quantity - filled; }
        public boolean isFilled() { return filled >= quantity; }
    }

    public static class Trade {
        public final String id;
        public final String ticker;
        public final double price;
        public final int quantity;
        public final String buyerUuid;
        public final String sellerUuid;
        public final String buyOrderId;
        public final String sellOrderId;
        public final long timestamp;

        public Trade(String id, String ticker, double price, int quantity,
                     String buyerUuid, String sellerUuid,
                     String buyOrderId, String sellOrderId, long timestamp) {
            this.id = id; this.ticker = ticker; this.price = price;
            this.quantity = quantity; this.buyerUuid = buyerUuid;
            this.sellerUuid = sellerUuid; this.buyOrderId = buyOrderId;
            this.sellOrderId = sellOrderId; this.timestamp = timestamp;
        }
    }

    public static class OrderBookSnapshot {
        public final List<Level> bids;
        public final List<Level> asks;
        public final long timestamp;

        public OrderBookSnapshot(List<Level> bids, List<Level> asks) {
            this.bids = bids; this.asks = asks; this.timestamp = System.currentTimeMillis();
        }

        public static class Level {
            public final double price;
            public final int quantity;
            public final int orderCount;
            public Level(double price, int quantity, int orderCount) {
                this.price = price; this.quantity = quantity; this.orderCount = orderCount;
            }
        }
    }

    public static class TickerSnapshot {
        public final String ticker;
        public final double lastPrice;
        public final double bidPrice;
        public final double askPrice;
        public final double spread;
        public final double change24h;
        public final int volume24h;
        public final double high24h;
        public final double low24h;

        public TickerSnapshot(String ticker, double lastPrice, double bidPrice, double askPrice,
                              double change24h, int volume24h, double high24h, double low24h) {
            this.ticker = ticker; this.lastPrice = lastPrice; this.bidPrice = bidPrice;
            this.askPrice = askPrice; this.spread = askPrice - bidPrice;
            this.change24h = change24h; this.volume24h = volume24h;
            this.high24h = high24h; this.low24h = low24h;
        }
    }

    // ── In-Memory State ──────────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, NavigableMap<Double, List<Order>>> bidsByTicker = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, NavigableMap<Double, List<Order>>> asksByTicker = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, HashMap<String, Integer>> playerShares = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, HashMap<String, Double>> playerAvgPrice = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> lastPrices = new ConcurrentHashMap<>();
    private static boolean loaded = false;
    private static boolean paused = false;

    // ── Pause / Resume ───────────────────────────────────────────────────────

    public static boolean isPaused() { return paused; }

    public static void pause() { paused = true; Bukkit.getLogger().warning("[Market] Stock market PAUSED."); }

    public static void resume() { paused = false; Bukkit.getLogger().info("[Market] Stock market RESUMED."); }

    // ── Inject Test Trade ────────────────────────────────────────────────────

    public static Trade injectTrade(String ticker, double price, int quantity) {
        if (!loaded) return null;
        ticker = ticker.toUpperCase();
        lastPrices.put(ticker, price);
        Trade trade = new Trade(
                generateId("trd_"), ticker, price, quantity,
                "00000000-0000-0000-0000-000000000000",
                "00000000-0000-0000-0000-000000000000",
                "injected", "injected", System.currentTimeMillis()
        );
        saveTrade(trade);
        broadcastTrades(List.of(trade));
        Bukkit.getLogger().info("[Market] Injected trade: " + quantity + "x " + ticker + " @ $" + price);
        return trade;
    }

    // ── Initialization ───────────────────────────────────────────────────────

    public static void loadFromDatabase() {
        bidsByTicker.clear(); asksByTicker.clear();
        playerShares.clear(); playerAvgPrice.clear();
        lastPrices.clear();

        loadOpenOrders();
        loadPortfolios();
        loadLastPrices();

        loaded = true;
        Bukkit.getLogger().info("[Market] Stock market engine loaded.");
    }

    private static void loadOpenOrders() {
        String sql = "SELECT * FROM stock_orders WHERE status IN ('OPEN', 'PARTIALLY_FILLED')";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                Order order = mapRowToOrder(rs);
                if (order != null) {
                    addToMemoryBook(order);
                    count++;
                }
            }
            Bukkit.getLogger().info("[Market] Loaded " + count + " open orders.");
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to load open orders: " + e.getMessage());
        }
    }

    private static void loadPortfolios() {
        String sql = "SELECT * FROM stock_portfolios";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String uuid = rs.getString("uuid");
                String ticker = rs.getString("ticker");
                int shares = rs.getInt("shares");
                double avgPrice = rs.getDouble("avg_entry_price");
                playerShares.computeIfAbsent(uuid, k -> new HashMap<>()).put(ticker, shares);
                playerAvgPrice.computeIfAbsent(uuid, k -> new HashMap<>()).put(ticker, avgPrice);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to load portfolios: " + e.getMessage());
        }
    }

    private static void loadLastPrices() {
        String sql = "SELECT ticker, price, quantity, timestamp FROM stock_trades " +
                "WHERE id IN (SELECT id FROM (SELECT id, ticker, MAX(timestamp) FROM stock_trades GROUP BY ticker))";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                lastPrices.put(rs.getString("ticker"), rs.getDouble("price"));
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to load last prices: " + e.getMessage());
        }
    }

    // ── Placing Orders ───────────────────────────────────────────────────────

    public static class OrderResult {
        public final boolean success;
        public final String error;
        public final Order order;
        public final List<Trade> trades;

        public OrderResult(boolean success, String error, Order order, List<Trade> trades) {
            this.success = success; this.error = error;
            this.order = order; this.trades = trades;
        }
    }

    public static OrderResult placeOrder(String playerUuid, String ticker, OrderType type,
                                         OrderSide side, double price, int quantity) {
        if (!loaded) return new OrderResult(false, "Market engine not initialized", null, null);
        if (paused) return new OrderResult(false, "Stock market is currently paused by an admin", null, null);
        if (quantity <= 0) return new OrderResult(false, "Quantity must be positive", null, null);

        ticker = ticker.toUpperCase();
        double lastPrice = getLastPrice(ticker);
        double dynamicPrice = DynamicPriceManager.getDynamicPrice(ticker);

        // Clamp limit price within ±20% of last/dynamic price to prevent manipulation
        double referencePrice = lastPrice > 0 ? lastPrice : (dynamicPrice > 0 ? dynamicPrice : 100);
        if (type == OrderType.LIMIT && price > 0) {
            double maxAllowed = referencePrice * 1.20;
            double minAllowed = referencePrice * 0.80;
            if (price > maxAllowed) price = maxAllowed;
            if (price < minAllowed) price = minAllowed;
        }

        // Validate balance/shares
        if (side == OrderSide.BUY) {
            double cost = (type == OrderType.MARKET) ? referencePrice * quantity : price * quantity;
            if (!EconomyUtils.hasBalance(UUID.fromString(playerUuid), cost)) {
                return new OrderResult(false, "Insufficient balance", null, null);
            }
        } else {
            int owned = getShares(playerUuid, ticker);
            if (owned < quantity) {
                return new OrderResult(false, "Insufficient shares. You own " + owned, null, null);
            }
        }

        String orderId = generateId("ord_");
        long now = System.currentTimeMillis();
        double execPrice = (type == OrderType.MARKET || price <= 0) ? 0 : price;

        Order order = new Order(orderId, ticker, playerUuid, type, side, execPrice,
                quantity, 0, OrderStatus.OPEN, 0, now);
        List<Trade> trades = new ArrayList<>();

        // Market orders — execute immediately
        if (type == OrderType.MARKET) {
            trades = matchOrder(order, referencePrice);
            if (trades.isEmpty()) {
                return new OrderResult(false, "No matching orders found on the order book", null, null);
            }
            order.status = order.isFilled() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            saveOrder(order);
            broadcastTrades(trades);
            return new OrderResult(true, null, order, trades);
        }

        // Stop orders — check trigger immediately, then place in book
        if (type == OrderType.STOP_LOSS) {
            if (price <= 0) return new OrderResult(false, "Stop price required", null, null);
            if (side == OrderSide.SELL && referencePrice > 0 && referencePrice <= price) {
                order.type = OrderType.MARKET;
                trades = matchOrder(order, referencePrice);
                order.status = order.isFilled() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            }
            if (trades.isEmpty()) {
                addToMemoryBook(order);
            }
            saveOrder(order);
            broadcastTrades(trades);
            return new OrderResult(true, null, order, trades);
        }

        if (type == OrderType.TAKE_PROFIT) {
            if (price <= 0) return new OrderResult(false, "Target price required", null, null);
            if (side == OrderSide.BUY && referencePrice > 0 && referencePrice >= price) {
                order.type = OrderType.MARKET;
                trades = matchOrder(order, referencePrice);
                order.status = order.isFilled() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            }
            if (trades.isEmpty()) {
                addToMemoryBook(order);
            }
            saveOrder(order);
            broadcastTrades(trades);
            return new OrderResult(true, null, order, trades);
        }

        // Limit order — try to match, then place remainder in book
        if (side == OrderSide.BUY) {
            trades = matchBid(order);
        } else {
            trades = matchAsk(order);
        }

        if (order.isFilled()) {
            order.status = OrderStatus.FILLED;
        } else if (!trades.isEmpty()) {
            order.status = OrderStatus.PARTIALLY_FILLED;
        }

        if (!order.isFilled()) {
            addToMemoryBook(order);
        }

        saveOrder(order);
        broadcastTrades(trades);
        return new OrderResult(true, null, order, trades);
    }

    private static List<Trade> matchOrder(Order order, double referencePrice) {
        if (order.side == OrderSide.BUY) {
            return matchBuyMarket(order, referencePrice);
        } else {
            return matchSellMarket(order, referencePrice);
        }
    }

    private static List<Trade> matchBuyMarket(Order order, double referencePrice) {
        List<Trade> trades = new ArrayList<>();
        NavigableMap<Double, List<Order>> asks = asksByTicker.get(order.ticker);
        if (asks == null) return trades;

        int remaining = order.remaining();
        double totalCost = 0;
        boolean insufficientFunds = false;

        Set<Double> emptyLevels = new HashSet<>();

        for (Map.Entry<Double, List<Order>> entry : asks.entrySet()) {
            if (remaining <= 0) break;
            List<Order> levelOrders = new ArrayList<>(entry.getValue());

            for (Order askOrder : levelOrders) {
                if (remaining <= 0) break;
                int matchQty = Math.min(remaining, askOrder.remaining());
                double cost = askOrder.price * matchQty;

                if (!insufficientFunds) {
                    if (!EconomyUtils.hasBalance(UUID.fromString(order.playerUuid), totalCost + cost)) {
                        insufficientFunds = true;
                        // Refund what we've taken so far
                        for (Trade t : trades) {
                            EconomyUtils.deposit(UUID.fromString(order.playerUuid), t.price * t.quantity);
                            EconomyUtils.withdraw(UUID.fromString(t.sellerUuid), t.price * t.quantity);
                            addShares(t.sellerUuid, t.ticker, t.quantity);
                            removeShares(t.buyerUuid, t.ticker, t.quantity);
                        }
                        trades.clear();
                        totalCost = 0;
                        remaining = order.remaining();
                        // Try limited funds — buy what we can
                        double available = EconomyUtils.getBalance(UUID.fromString(order.playerUuid));
                        if (available <= 0) return trades;
                        matchQty = Math.min(remaining, (int) (available / askOrder.price));
                        if (matchQty <= 0) continue;
                        cost = askOrder.price * matchQty;
                        if (!EconomyUtils.hasBalance(UUID.fromString(order.playerUuid), cost)) continue;
                    }
                }

                Trade trade = executeTrade(order, askOrder, matchQty);
                trades.add(trade);
                totalCost += cost;
                remaining -= matchQty;
                order.filled += matchQty;
                askOrder.filled += matchQty;

                if (askOrder.isFilled()) {
                    askOrder.status = OrderStatus.FILLED;
                    saveOrder(askOrder);
                    entry.getValue().remove(askOrder);
                }
            }

            if (entry.getValue().isEmpty()) {
                emptyLevels.add(entry.getKey());
            }
        }

        for (Double level : emptyLevels) asks.remove(level);
        return trades;
    }

    private static List<Trade> matchSellMarket(Order order, double referencePrice) {
        List<Trade> trades = new ArrayList<>();
        NavigableMap<Double, List<Order>> bids = bidsByTicker.get(order.ticker);
        if (bids == null) return trades;

        int remaining = order.remaining();

        Set<Double> emptyLevels = new HashSet<>();
        for (Map.Entry<Double, List<Order>> entry : bids.entrySet()) {
            if (remaining <= 0) break;
            List<Order> levelOrders = new ArrayList<>(entry.getValue());

            for (Order bidOrder : levelOrders) {
                if (remaining <= 0) break;
                int matchQty = Math.min(remaining, bidOrder.remaining());
                Trade trade = executeTrade(bidOrder, order, matchQty);
                trades.add(trade);
                remaining -= matchQty;
                order.filled += matchQty;
                bidOrder.filled += matchQty;

                if (bidOrder.isFilled()) {
                    bidOrder.status = OrderStatus.FILLED;
                    saveOrder(bidOrder);
                    entry.getValue().remove(bidOrder);
                }
            }

            if (entry.getValue().isEmpty()) {
                emptyLevels.add(entry.getKey());
            }
        }

        for (Double level : emptyLevels) bids.remove(level);
        return trades;
    }

    private static List<Trade> matchBid(Order order) {
        List<Trade> trades = new ArrayList<>();
        NavigableMap<Double, List<Order>> asks = asksByTicker.get(order.ticker);
        if (asks == null) return trades;

        int remaining = order.remaining();
        Set<Double> emptyLevels = new HashSet<>();

        // Match against asks at or below bid price
        for (Map.Entry<Double, List<Order>> entry : asks.entrySet()) {
            if (remaining <= 0) break;
            if (entry.getKey() > order.price) break; // asks too expensive

            List<Order> levelOrders = new ArrayList<>(entry.getValue());
            for (Order askOrder : levelOrders) {
                if (remaining <= 0) break;
                int matchQty = Math.min(remaining, askOrder.remaining());
                Trade trade = executeTrade(order, askOrder, matchQty);
                trades.add(trade);
                remaining -= matchQty;
                order.filled += matchQty;
                askOrder.filled += matchQty;

                if (askOrder.isFilled()) {
                    askOrder.status = OrderStatus.FILLED;
                    saveOrder(askOrder);
                    entry.getValue().remove(askOrder);
                }
            }
            if (entry.getValue().isEmpty()) emptyLevels.add(entry.getKey());
        }

        for (Double level : emptyLevels) asks.remove(level);
        return trades;
    }

    private static List<Trade> matchAsk(Order order) {
        List<Trade> trades = new ArrayList<>();
        NavigableMap<Double, List<Order>> bids = bidsByTicker.get(order.ticker);
        if (bids == null) return trades;

        int remaining = order.remaining();
        Set<Double> emptyLevels = new HashSet<>();

        // Match against bids at or above ask price
        for (Map.Entry<Double, List<Order>> entry : bids.entrySet()) {
            if (remaining <= 0) break;
            if (entry.getKey() < order.price) break; // bids too low

            List<Order> levelOrders = new ArrayList<>(entry.getValue());
            for (Order bidOrder : levelOrders) {
                if (remaining <= 0) break;
                int matchQty = Math.min(remaining, bidOrder.remaining());
                Trade trade = executeTrade(bidOrder, order, matchQty);
                trades.add(trade);
                remaining -= matchQty;
                order.filled += matchQty;
                bidOrder.filled += matchQty;

                if (bidOrder.isFilled()) {
                    bidOrder.status = OrderStatus.FILLED;
                    saveOrder(bidOrder);
                    entry.getValue().remove(bidOrder);
                }
            }
            if (entry.getValue().isEmpty()) emptyLevels.add(entry.getKey());
        }

        for (Double level : emptyLevels) bids.remove(level);
        return trades;
    }

    private static Trade executeTrade(Order buyOrder, Order sellOrder, int quantity) {
        double tradePrice = sellOrder.price > 0 ? sellOrder.price :
                (buyOrder.price > 0 ? buyOrder.price : getLastPrice(buyOrder.ticker));

        // Withdraw money from buyer
        EconomyUtils.withdraw(UUID.fromString(buyOrder.playerUuid), tradePrice * quantity);
        // Deposit money to seller
        EconomyUtils.deposit(UUID.fromString(sellOrder.playerUuid), tradePrice * quantity);

        // Transfer shares
        removeShares(sellOrder.playerUuid, buyOrder.ticker, quantity);
        addShares(buyOrder.playerUuid, buyOrder.ticker, quantity);

        // Update last price
        lastPrices.put(buyOrder.ticker, tradePrice);

        Trade trade = new Trade(
                generateId("trd_"), buyOrder.ticker, tradePrice, quantity,
                buyOrder.playerUuid, sellOrder.playerUuid,
                buyOrder.id, sellOrder.id, System.currentTimeMillis()
        );

        saveTrade(trade);
        return trade;
    }

    // ── Cancelling Orders ────────────────────────────────────────────────────

    public static boolean cancelOrder(String orderId, String playerUuid) {
        String sql = "SELECT * FROM stock_orders WHERE id = ? AND player_uuid = ? AND status IN ('OPEN', 'PARTIALLY_FILLED')";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            stmt.setString(2, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Order order = mapRowToOrder(rs);
                    if (order != null) {
                        removeFromMemoryBook(order);
                        String update = "UPDATE stock_orders SET status = 'CANCELLED' WHERE id = ?";
                        try (PreparedStatement up = conn.prepareStatement(update)) {
                            up.setString(1, orderId);
                            up.executeUpdate();
                        }
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to cancel order " + orderId + ": " + e.getMessage());
        }
        return false;
    }

    // ── Order Book Queries ───────────────────────────────────────────────────

    public static OrderBookSnapshot getOrderBook(String ticker, int depth) {
        ticker = ticker.toUpperCase();
        List<OrderBookSnapshot.Level> bidLevels = new ArrayList<>();
        List<OrderBookSnapshot.Level> askLevels = new ArrayList<>();

        NavigableMap<Double, List<Order>> bids = bidsByTicker.get(ticker);
        if (bids != null) {
            int count = 0;
            for (Map.Entry<Double, List<Order>> entry : bids.descendingMap().entrySet()) {
                if (count >= depth) break;
                int qty = entry.getValue().stream().mapToInt(Order::remaining).sum();
                bidLevels.add(new OrderBookSnapshot.Level(entry.getKey(), qty, entry.getValue().size()));
                count++;
            }
        }

        NavigableMap<Double, List<Order>> asks = asksByTicker.get(ticker);
        if (asks != null) {
            int count = 0;
            for (Map.Entry<Double, List<Order>> entry : asks.entrySet()) {
                if (count >= depth) break;
                int qty = entry.getValue().stream().mapToInt(Order::remaining).sum();
                askLevels.add(new OrderBookSnapshot.Level(entry.getKey(), qty, entry.getValue().size()));
                count++;
            }
        }

        return new OrderBookSnapshot(bidLevels, askLevels);
    }

    public static double getBidPrice(String ticker) {
        NavigableMap<Double, List<Order>> bids = bidsByTicker.get(ticker.toUpperCase());
        if (bids == null || bids.isEmpty()) return 0;
        return bids.lastKey();
    }

    public static double getAskPrice(String ticker) {
        NavigableMap<Double, List<Order>> asks = asksByTicker.get(ticker.toUpperCase());
        if (asks == null || asks.isEmpty()) return 0;
        return asks.firstKey();
    }

    public static double getLastPrice(String ticker) {
        return lastPrices.getOrDefault(ticker.toUpperCase(), 0.0);
    }

    public static double getSpread(String ticker) {
        double bid = getBidPrice(ticker);
        double ask = getAskPrice(ticker);
        if (bid <= 0 || ask <= 0) return 0;
        return ask - bid;
    }

    // ── Portfolio ────────────────────────────────────────────────────────────

    public static int getShares(String uuid, String ticker) {
        HashMap<String, Integer> holdings = playerShares.get(uuid);
        return holdings != null ? holdings.getOrDefault(ticker.toUpperCase(), 0) : 0;
    }

    public static double getAvgEntryPrice(String uuid, String ticker) {
        HashMap<String, Double> prices = playerAvgPrice.get(uuid);
        return prices != null ? prices.getOrDefault(ticker.toUpperCase(), 0.0) : 0.0;
    }

    public static Map<String, Object> getPortfolio(String uuid) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> holdings = new ArrayList<>();
        double totalValue = 0;
        double totalCost = 0;

        HashMap<String, Integer> shares = playerShares.get(uuid);
        if (shares != null) {
            for (Map.Entry<String, Integer> entry : shares.entrySet()) {
                if (entry.getValue() <= 0) continue;
                String ticker = entry.getKey();
                int qty = entry.getValue();
                double avgPrice = getAvgEntryPrice(uuid, ticker);
                double currentPrice = getLastPrice(ticker);
                if (currentPrice <= 0) currentPrice = DynamicPriceManager.getDynamicPrice(ticker);
                double value = currentPrice * qty;
                double cost = avgPrice * qty;
                totalValue += value;
                totalCost += cost;

                Map<String, Object> h = new LinkedHashMap<>();
                h.put("ticker", ticker);
                h.put("shares", qty);
                h.put("avg_entry_price", avgPrice);
                h.put("current_price", currentPrice);
                h.put("value", value);
                h.put("pnl", value - cost);
                h.put("pnl_percent", cost > 0 ? ((value - cost) / cost) * 100 : 0);
                holdings.add(h);
            }
        }

        result.put("holdings", holdings);
        result.put("total_value", totalValue);
        result.put("total_cost", totalCost);
        result.put("total_pnl", totalValue - totalCost);
        result.put("total_pnl_percent", totalCost > 0 ? ((totalValue - totalCost) / totalCost) * 100 : 0);
        result.put("balance", EconomyUtils.getBalance(UUID.fromString(uuid)));
        return result;
    }

    public static void addShares(String uuid, String ticker, int quantity) {
        ticker = ticker.toUpperCase();
        HashMap<String, Integer> holdings = playerShares.computeIfAbsent(uuid, k -> new HashMap<>());
        int current = holdings.getOrDefault(ticker, 0);
        holdings.put(ticker, current + quantity);
        savePortfolio(uuid, ticker);
    }

    public static void removeShares(String uuid, String ticker, int quantity) {
        ticker = ticker.toUpperCase();
        HashMap<String, Integer> holdings = playerShares.get(uuid);
        if (holdings == null) return;
        int current = holdings.getOrDefault(ticker, 0);
        int updated = Math.max(0, current - quantity);
        holdings.put(ticker, updated);
        savePortfolio(uuid, ticker);
    }

    // ── Open Orders Query ────────────────────────────────────────────────────

    public static List<Order> getOpenOrders(String playerUuid) {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM stock_orders WHERE player_uuid = ? AND status IN ('OPEN', 'PARTIALLY_FILLED') ORDER BY created_at DESC";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) orders.add(mapRowToOrder(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return orders;
    }

    public static List<Order> getOrderHistory(String playerUuid, int limit) {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM stock_orders WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUuid);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) orders.add(mapRowToOrder(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return orders;
    }

    // ── Recent Trades Query ──────────────────────────────────────────────────

    public static List<Trade> getRecentTrades(String ticker, int limit) {
        List<Trade> trades = new ArrayList<>();
        String sql = "SELECT * FROM stock_trades WHERE ticker = ? ORDER BY timestamp DESC LIMIT ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, ticker.toUpperCase());
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) trades.add(mapRowToTrade(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return trades;
    }

    // ── Ticker Snapshot ──────────────────────────────────────────────────────

    public static TickerSnapshot getTickerSnapshot(String ticker) {
        ticker = ticker.toUpperCase();
        double lastPrice = getLastPrice(ticker);
        if (lastPrice <= 0) {
            double dynPrice = DynamicPriceManager.getDynamicPrice(ticker);
            if (dynPrice > 0) lastPrice = dynPrice;
            else lastPrice = 0;
        }
        double bid = getBidPrice(ticker);
        double ask = getAskPrice(ticker);

        // 24h stats
        long dayAgo = System.currentTimeMillis() - (24L * 60L * 60L * 1000L);
        double high = lastPrice, low = lastPrice;
        int volume = 0;
        double openPrice = lastPrice;

        String sql = "SELECT price, quantity, timestamp FROM stock_trades WHERE ticker = ? AND timestamp > ? ORDER BY timestamp ASC";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, ticker);
            stmt.setLong(2, dayAgo);
            try (ResultSet rs = stmt.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    double p = rs.getDouble("price");
                    int q = rs.getInt("quantity");
                    if (first) { openPrice = p; first = false; }
                    if (p > high) high = p;
                    if (p < low) low = p;
                    volume += q;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        double change = openPrice > 0 ? ((lastPrice - openPrice) / openPrice) * 100 : 0;

        return new TickerSnapshot(ticker, lastPrice, bid, ask, change, volume, high, low);
    }

    // ── Stop Order Evaluation (called every 60s) ────────────────────────────

    public static void evaluateStopOrders() {
        if (paused) return;
        String sql = "SELECT * FROM stock_orders WHERE type IN ('STOP_LOSS', 'TAKE_PROFIT') AND status = 'OPEN'";
        List<Order> triggered = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Order order = mapRowToOrder(rs);
                if (order == null) continue;

                double currentPrice = getLastPrice(order.ticker);
                if (currentPrice <= 0) {
                    currentPrice = DynamicPriceManager.getDynamicPrice(order.ticker);
                }
                if (currentPrice <= 0) continue;

                boolean shouldTrigger = false;
                if (order.type == OrderType.STOP_LOSS) {
                    if (order.side == OrderSide.SELL && currentPrice <= order.stopPrice) {
                        shouldTrigger = true;
                    }
                } else if (order.type == OrderType.TAKE_PROFIT) {
                    if (order.side == OrderSide.SELL && currentPrice >= order.stopPrice) {
                        shouldTrigger = true;
                    }
                }

                if (shouldTrigger) {
                    triggered.add(order);
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to evaluate stop orders: " + e.getMessage());
        }

        for (Order order : triggered) {
            removeFromMemoryBook(order);
            double currentPrice = getLastPrice(order.ticker);
            if (currentPrice <= 0) currentPrice = DynamicPriceManager.getDynamicPrice(order.ticker);

            OrderResult result;
            if (order.side == OrderSide.SELL) {
                result = placeOrder(order.playerUuid, order.ticker, OrderType.MARKET, OrderSide.SELL, 0, order.remaining());
            } else {
                result = placeOrder(order.playerUuid, order.ticker, OrderType.MARKET, OrderSide.BUY, 0, order.remaining());
            }

            if (result.success) {
                String update = "UPDATE stock_orders SET status = 'FILLED', filled = quantity WHERE id = ?";
                try (Connection conn = DatabaseManager.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(update)) {
                    stmt.setString(1, order.id);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                Bukkit.getLogger().info("[Market] Stop order " + order.id + " triggered for " + order.ticker
                        + " at price " + currentPrice);
            }
        }

        if (!triggered.isEmpty()) {
            Bukkit.getLogger().info("[Market] Evaluated stop orders: " + triggered.size() + " triggered.");
        }
    }

    public static List<TickerSnapshot> getAllTickers() {
        Set<String> tickers = new HashSet<>();
        tickers.addAll(bidsByTicker.keySet());
        tickers.addAll(asksByTicker.keySet());
        tickers.addAll(lastPrices.keySet());

        // Also include all items that have dynamic prices
        for (org.bukkit.Material m : org.bukkit.Material.values()) {
            if (m.isItem() && !m.isAir()) tickers.add(m.name());
        }

        return tickers.stream()
                .filter(t -> DynamicPriceManager.getDynamicPrice(t) > 0 || getLastPrice(t) > 0)
                .map(StockMarketEngine::getTickerSnapshot)
                .sorted((a, b) -> Double.compare(Math.abs(b.change24h), Math.abs(a.change24h)))
                .collect(Collectors.toList());
    }

    // ── DB Persistence ───────────────────────────────────────────────────────

    private static void addToMemoryBook(Order order) {
        if (order.side == OrderSide.BUY) {
            NavigableMap<Double, List<Order>> bids = bidsByTicker
                    .computeIfAbsent(order.ticker, k -> new TreeMap<>(Collections.reverseOrder()));
            bids.computeIfAbsent(order.price, k -> Collections.synchronizedList(new ArrayList<>())).add(order);
        } else {
            NavigableMap<Double, List<Order>> asks = asksByTicker
                    .computeIfAbsent(order.ticker, k -> new TreeMap<>());
            asks.computeIfAbsent(order.price, k -> Collections.synchronizedList(new ArrayList<>())).add(order);
        }
    }

    private static void removeFromMemoryBook(Order order) {
        if (order.side == OrderSide.BUY) {
            NavigableMap<Double, List<Order>> bids = bidsByTicker.get(order.ticker);
            if (bids != null) {
                List<Order> atPrice = bids.get(order.price);
                if (atPrice != null) { atPrice.remove(order); if (atPrice.isEmpty()) bids.remove(order.price); }
                if (bids.isEmpty()) bidsByTicker.remove(order.ticker);
            }
        } else {
            NavigableMap<Double, List<Order>> asks = asksByTicker.get(order.ticker);
            if (asks != null) {
                List<Order> atPrice = asks.get(order.price);
                if (atPrice != null) { atPrice.remove(order); if (atPrice.isEmpty()) asks.remove(order.price); }
                if (asks.isEmpty()) asksByTicker.remove(order.ticker);
            }
        }
    }

    private static void saveOrder(Order order) {
        String sql = "INSERT OR REPLACE INTO stock_orders (id, ticker, player_uuid, type, side, price, quantity, filled, status, stop_price, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, order.id);
            stmt.setString(2, order.ticker);
            stmt.setString(3, order.playerUuid);
            stmt.setString(4, order.type.name());
            stmt.setString(5, order.side.name());
            stmt.setDouble(6, order.price);
            stmt.setInt(7, order.quantity);
            stmt.setInt(8, order.filled);
            stmt.setString(9, order.status.name());
            stmt.setDouble(10, order.stopPrice);
            stmt.setLong(11, order.createdAt);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to save order " + order.id + ": " + e.getMessage());
        }
    }

    private static void saveTrade(Trade trade) {
        String sql = "INSERT INTO stock_trades (id, ticker, price, quantity, buyer_uuid, seller_uuid, buy_order_id, sell_order_id, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, trade.id);
            stmt.setString(2, trade.ticker);
            stmt.setDouble(3, trade.price);
            stmt.setInt(4, trade.quantity);
            stmt.setString(5, trade.buyerUuid);
            stmt.setString(6, trade.sellerUuid);
            stmt.setString(7, trade.buyOrderId);
            stmt.setString(8, trade.sellOrderId);
            stmt.setLong(9, trade.timestamp);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to save trade: " + e.getMessage());
        }
    }

    private static void savePortfolio(String uuid, String ticker) {
        HashMap<String, Integer> holdings = playerShares.get(uuid);
        int shares = holdings != null ? holdings.getOrDefault(ticker, 0) : 0;
        double avgPrice = getAvgEntryPrice(uuid, ticker);

        String sql = "INSERT OR REPLACE INTO stock_portfolios (uuid, ticker, shares, avg_entry_price) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            stmt.setString(2, ticker);
            stmt.setInt(3, shares);
            stmt.setDouble(4, avgPrice);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to save portfolio for " + uuid + ": " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Order mapRowToOrder(ResultSet rs) throws SQLException {
        return new Order(
                rs.getString("id"), rs.getString("ticker"),
                rs.getString("player_uuid"),
                OrderType.valueOf(rs.getString("type")),
                OrderSide.valueOf(rs.getString("side")),
                rs.getDouble("price"), rs.getInt("quantity"),
                rs.getInt("filled"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getDouble("stop_price"), rs.getLong("created_at")
        );
    }

    private static Trade mapRowToTrade(ResultSet rs) throws SQLException {
        return new Trade(
                rs.getString("id"), rs.getString("ticker"),
                rs.getDouble("price"), rs.getInt("quantity"),
                rs.getString("buyer_uuid"), rs.getString("seller_uuid"),
                rs.getString("buy_order_id"), rs.getString("sell_order_id"),
                rs.getLong("timestamp")
        );
    }

    private static String generateId(String prefix) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static void broadcastTrades(List<Trade> trades) {
        WebSocketManager.broadcastTrades(trades);
    }
}
