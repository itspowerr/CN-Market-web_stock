package net.craftnepal.market.stock;

import org.bukkit.Bukkit;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class WebSocketManager {

    private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-5AB5A0BD85B2";
    private static final int PING_INTERVAL_MS = 30000;
    private static final int TICKER_INTERVAL_MS = 5000;

    private static ServerSocket serverSocket;
    private static Thread listenerThread;
    private static Thread tickerThread;
    private static Thread pingThread;
    private static boolean running = false;
    private static int port;

    private static final ConcurrentHashMap<String, Set<WebSocketClient>> channels = new ConcurrentHashMap<>();
    private static final Set<WebSocketClient> allClients = ConcurrentHashMap.newKeySet();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public static synchronized void start(int wsPort) {
        if (running) return;
        port = wsPort;
        running = true;

        listenerThread = new Thread(WebSocketManager::acceptLoop, "ws-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        tickerThread = new Thread(WebSocketManager::tickerLoop, "ws-ticker");
        tickerThread.setDaemon(true);
        tickerThread.start();

        pingThread = new Thread(WebSocketManager::pingLoop, "ws-ping");
        pingThread.setDaemon(true);
        pingThread.start();

        Bukkit.getLogger().info("[Market] WebSocket server started on port " + port);
    }

    public static synchronized void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        for (WebSocketClient client : allClients) {
            client.close();
        }
        allClients.clear();
        channels.clear();
    }

    // ── Loops ─────────────────────────────────────────────────────────────────

    private static void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    WebSocketClient client = new WebSocketClient(socket);
                    allClients.add(client);
                    client.startReader();
                } catch (SocketException e) {
                    if (!running) break;
                } catch (IOException e) {
                    Bukkit.getLogger().warning("[Market] WS accept: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) {
                Bukkit.getLogger().severe("[Market] WS server failed: " + e.getMessage());
            }
        }
    }

    private static void tickerLoop() {
        while (running) {
            try {
                Thread.sleep(TICKER_INTERVAL_MS);
                if (!running) break;
                List<StockMarketEngine.TickerSnapshot> tickers = StockMarketEngine.getAllTickers();
                StringBuilder json = new StringBuilder("{\"type\":\"tickers\",\"data\":[");
                boolean first = true;
                for (StockMarketEngine.TickerSnapshot t : tickers) {
                    if (!first) json.append(",");
                    first = false;
                    json.append("{\"ticker\":\"").append(escapeJson(t.ticker))
                        .append("\",\"last_price\":").append(t.lastPrice)
                        .append(",\"bid\":").append(t.bidPrice)
                        .append(",\"ask\":").append(t.askPrice)
                        .append(",\"spread\":").append(t.spread)
                        .append(",\"change_24h_pct\":").append(t.change24h)
                        .append(",\"volume_24h\":").append(t.volume24h)
                        .append(",\"high_24h\":").append(t.high24h)
                        .append(",\"low_24h\":").append(t.low24h)
                        .append("}");
                }
                json.append("]}");
                broadcast("tickers", json.toString());
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static void pingLoop() {
        while (running) {
            try {
                Thread.sleep(PING_INTERVAL_MS);
                if (!running) break;
                for (WebSocketClient client : allClients) {
                    client.sendPing();
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ── Broadcasting ──────────────────────────────────────────────────────────

    public static void broadcast(String channel, String message) {
        Set<WebSocketClient> subs = channels.get(channel);
        if (subs == null || subs.isEmpty()) return;
        for (WebSocketClient client : subs) {
            client.send(message);
        }
    }

    public static void broadcastTrades(List<StockMarketEngine.Trade> trades) {
        if (trades == null || trades.isEmpty()) return;
        Set<String> affectedTickers = new HashSet<>();
        for (StockMarketEngine.Trade trade : trades) {
            affectedTickers.add(trade.ticker);
            StringBuilder json = new StringBuilder("{\"type\":\"trade\",\"data\":{");
            json.append("\"id\":\"").append(escapeJson(trade.id)).append("\"")
                .append(",\"ticker\":\"").append(escapeJson(trade.ticker)).append("\"")
                .append(",\"price\":").append(trade.price)
                .append(",\"quantity\":").append(trade.quantity)
                .append(",\"buyer_uuid\":\"").append(escapeJson(trade.buyerUuid)).append("\"")
                .append(",\"seller_uuid\":\"").append(escapeJson(trade.sellerUuid)).append("\"")
                .append(",\"timestamp\":").append(trade.timestamp)
                .append("}}");
            String msg = json.toString();
            broadcast("trades", msg);
            broadcast("trades." + trade.ticker, msg);
        }
        for (String ticker : affectedTickers) {
            broadcastOrderBook(ticker);
        }
    }

    public static void broadcastOrderBook(String ticker) {
        StockMarketEngine.OrderBookSnapshot ob = StockMarketEngine.getOrderBook(ticker, 10);
        StringBuilder json = new StringBuilder("{\"type\":\"orderbook\",\"ticker\":\"");
        json.append(escapeJson(ticker)).append("\",\"data\":{\"bids\":[");
        boolean first = true;
        for (StockMarketEngine.OrderBookSnapshot.Level level : ob.bids) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"price\":").append(level.price)
                .append(",\"quantity\":").append(level.quantity)
                .append(",\"orders\":").append(level.orderCount)
                .append("}");
        }
        json.append("],\"asks\":[");
        first = true;
        for (StockMarketEngine.OrderBookSnapshot.Level level : ob.asks) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"price\":").append(level.price)
                .append(",\"quantity\":").append(level.quantity)
                .append(",\"orderCount\":").append(level.orderCount)
                .append("}");
        }
        json.append("]}}");
        String msg = json.toString();
        broadcast("orderbooks", msg);
        broadcast("orderbook." + ticker, msg);
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    static void subscribe(WebSocketClient client, String channel) {
        channels.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(client);
    }

    static void unsubscribe(WebSocketClient client, String channel) {
        Set<WebSocketClient> subs = channels.get(channel);
        if (subs != null) {
            subs.remove(client);
            if (subs.isEmpty()) channels.remove(channel);
        }
    }

    static void removeClient(WebSocketClient client) {
        allClients.remove(client);
        for (Set<WebSocketClient> subs : channels.values()) {
            subs.remove(client);
        }
    }

    // ── JSON Escaping ─────────────────────────────────────────────────────────

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── WebSocket Client ──────────────────────────────────────────────────────

    public static class WebSocketClient {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private Thread readerThread;
        private volatile boolean connected = true;
        private final java.util.ArrayDeque<Long> messageTimestamps = new java.util.ArrayDeque<>();

        WebSocketClient(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            performHandshake();
        }

        private void performHandshake() throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String requestLine = reader.readLine();
            if (requestLine == null || !requestLine.startsWith("GET ")) {
                close(); return;
            }

            String key = null;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                if (header.regionMatches(true, 0, "Sec-WebSocket-Key:", 18, 18)) {
                    key = header.substring(header.indexOf(':') + 1).trim();
                }
            }

            if (key == null) {
                close(); return;
            }

            String acceptKey;
            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                sha1.update(key.getBytes());
                sha1.update(MAGIC_GUID.getBytes());
                acceptKey = Base64.getEncoder().encodeToString(sha1.digest());
            } catch (NoSuchAlgorithmException e) {
                close(); return;
            }

            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "\r\n";
            out.write(response.getBytes());
            out.flush();
        }

        void startReader() {
            readerThread = new Thread(this::readLoop, "ws-client-" + socket.getPort());
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void readLoop() {
            try {
                while (connected && running) {
                    int firstByte = in.read();
                    if (firstByte == -1) break;

                    int opcode = firstByte & 0x0F;
                    int secondByte = in.read();
                    if (secondByte == -1) break;

                    long payloadLen = secondByte & 0x7F;
                    if (payloadLen == 126) {
                        payloadLen = ((long) in.read() << 8) | (long) in.read();
                    } else if (payloadLen == 127) {
                        payloadLen = 0;
                        for (int i = 0; i < 8; i++) {
                            payloadLen = (payloadLen << 8) | (long) (in.read() & 0xFF);
                        }
                    }

                    byte[] maskKey = null;
                    if ((secondByte & 0x80) != 0) {
                        maskKey = new byte[4];
                        in.read(maskKey);
                    }

                    byte[] payload = new byte[(int) payloadLen];
                    int read = 0;
                    while (read < payloadLen) {
                        int n = in.read(payload, read, (int) (payloadLen - read));
                        if (n == -1) break;
                        read += n;
                    }

                    if (maskKey != null) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] ^= maskKey[i & 3];
                        }
                    }

                    handleFrame(opcode, payload);
                }
            } catch (SocketException e) {
                // expected on close
            } catch (IOException e) {
                if (connected) {
                    Bukkit.getLogger().warning("[Market] WS client error: " + e.getMessage());
                }
            } finally {
                close();
            }
        }

        private void handleFrame(int opcode, byte[] payload) {
            switch (opcode) {
                case 0x8 -> close();
                case 0x9 -> {
                    try { sendFrame(0xA, payload); } catch (IOException e) { close(); }
                }
                case 0x1 -> {
                    String text = new String(payload);
                    handleMessage(text);
                }
            }
        }

        private void handleMessage(String text) {
            long now = System.currentTimeMillis();
            synchronized (messageTimestamps) {
                while (!messageTimestamps.isEmpty() && messageTimestamps.peekFirst() < now - 10_000) {
                    messageTimestamps.pollFirst();
                }
                if (messageTimestamps.size() >= 30) {
                    Bukkit.getLogger().warning("[Market] WS client " + socket.getPort() + " rate limited (30 msg/10s)");
                    close();
                    return;
                }
                messageTimestamps.addLast(now);
            }
            if (text.contains("\"subscribe\"")) {
                String channel = extractJsonString(text, "channel");
                if (channel != null) subscribe(this, channel);
            } else if (text.contains("\"unsubscribe\"")) {
                String channel = extractJsonString(text, "channel");
                if (channel != null) unsubscribe(this, channel);
            }
        }

        private String extractJsonString(String json, String key) {
            String search = "\"" + key + "\"";
            int idx = json.indexOf(search);
            if (idx == -1) return null;
            idx = json.indexOf(':', idx + search.length());
            if (idx == -1) return null;
            int start = json.indexOf('"', idx);
            if (start == -1) return null;
            int end = json.indexOf('"', start + 1);
            if (end == -1) return null;
            return json.substring(start + 1, end);
        }

        synchronized void send(String text) {
            if (!connected) return;
            try {
                sendFrame(0x1, text.getBytes());
            } catch (IOException e) {
                close();
            }
        }

        void sendPing() {
            if (!connected) return;
            try {
                sendFrame(0x9, new byte[0]);
            } catch (IOException e) {
                close();
            }
        }

        private void sendFrame(int opcode, byte[] data) throws IOException {
            out.write(0x80 | opcode);
            if (data.length < 126) {
                out.write(data.length);
            } else if (data.length < 65536) {
                out.write(126);
                out.write(data.length >> 8);
                out.write(data.length & 0xFF);
            } else {
                out.write(127);
                long len = data.length;
                for (int i = 7; i >= 0; i--) {
                    out.write((int) (len >> (i * 8)) & 0xFF);
                }
            }
            out.write(data);
            out.flush();
        }

        synchronized void close() {
            if (!connected) return;
            connected = false;
            try {
                try { sendFrame(0x8, new byte[0]); } catch (IOException ignored) {}
                socket.close();
            } catch (IOException ignored) {}
            removeClient(this);
        }
    }
    // ── end WebSocketClient ───────────────────────────────────────────────────
}
