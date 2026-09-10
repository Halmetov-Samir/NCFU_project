package app;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class WorkerApplication {

    // ============ CIRCUIT BREAKER ============
    static class CircuitBreaker {
        enum State { CLOSED, OPEN, HALF_OPEN }
        private final int threshold; private final long timeoutMs;
        private int failures = 0; private long openedAt = 0;
        private State state = State.CLOSED;
        CircuitBreaker(int t, long to) { threshold = t; timeoutMs = to; }
        synchronized boolean allow() {
            if (state == State.CLOSED) return true;
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - openedAt > timeoutMs) {
                    state = State.HALF_OPEN; return true;
                }
                return false;
            }
            return true;
        }
        synchronized void success() { failures = 0; state = State.CLOSED; }
        synchronized void failure() {
            if (++failures >= threshold) { state = State.OPEN; openedAt = System.currentTimeMillis(); }
        }
    }

    // ============ HTTP CLIENT ============
    static class ServerClient {
        private final String base; private final HttpClient http;
        private final ObjectMapper mapper = new ObjectMapper();
        private final CircuitBreaker breaker = new CircuitBreaker(3, 5000);
        ServerClient(String base) {
            this.base = base;
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        }
        Map<String, Object> register(String id) throws Exception {
            return post("/api/register", Map.of("workerId", id));
        }
        Map<String, Object> nextRange(String id) {
            try { return post("/api/next-range", Map.of("workerId", id)); }
            catch (Exception e) { return Map.of("stop", true); }
        }
        void reportResult(String id, String pin, String hash) {
            try { post("/api/result", Map.of("workerId", id, "pin", pin, "hash", hash)); }
            catch (Exception e) { System.err.println("result err: " + e.getMessage()); }
        }
        void reportProgress(String id, long checked) {
            try { post("/api/progress", Map.of("workerId", id, "checked", checked)); }
            catch (Exception ignored) {}
        }
        void heartbeat(String id) {
            try { post("/api/heartbeat", Map.of("workerId", id)); }
            catch (Exception ignored) {}
        }
        Map<String, Object> getStatus() {
            if (!breaker.allow()) return Map.of("stop", false, "foundPin", "");
            try {
                var r = get("/api/status");
                breaker.success();
                return r;
            } catch (Exception e) {
                breaker.failure();
                return Map.of("stop", false, "foundPin", "");
            }
        }
        private Map<String, Object> post(String path, Map<String, Object> body) throws Exception {
            var req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("ngrok-skip-browser-warning", "1")
                    .header("User-Agent", "MD5Worker/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            return mapper.readValue(resp, Map.class);
        }
        @SuppressWarnings("unchecked")
        private Map<String, Object> get(String path) throws Exception {
            var req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("ngrok-skip-browser-warning", "1")
                    .header("User-Agent", "MD5Worker/1.0")
                    .GET()
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            return mapper.readValue(resp, Map.class);
        }
    }

    // ============ TEMPLATE METHOD ============
    static abstract class AbstractBruteForcer {
        protected final ServerClient client; protected final String workerId;
        protected final int pinLength; protected final String targetHash;
        protected final AtomicBoolean found = new AtomicBoolean(false);
        AbstractBruteForcer(ServerClient c, String id, int pinLength, String h) {
            client = c; workerId = id; this.pinLength = pinLength; targetHash = h;
        }
        public final void run() throws Exception {
            prepare();
            // Цикл: берём диапазон → перебираем → просим следующий
            while (!found.get()) {
                long[] range = requestRange();
                if (range == null) break;
                iterate(range[0], range[1]);
                if (found.get()) break;
            }
            onFound();
        }
        protected abstract void prepare();
        protected abstract void iterate(long start, long end) throws Exception;
        protected abstract void onFound();
        protected abstract long[] requestRange() throws Exception;

        protected static String md5(String s) {
            try {
                var md = MessageDigest.getInstance("MD5");
                var d = md.digest(s.getBytes(StandardCharsets.UTF_8));
                var sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    // ============ CONCRETE ============
    static class Md5BruteForcer extends AbstractBruteForcer {
        private static final String POISON = "__END__";
        private final AtomicLong totalChecked = new AtomicLong(0);
        private final ConcurrentLinkedDeque<long[]> extraRanges = new ConcurrentLinkedDeque<>();
        private ScheduledExecutorService monitor;
        private BlockingQueue<String> queue;
        private ExecutorService pool;
        private int threads;

        Md5BruteForcer(ServerClient c, String id, int pinLength, String h) {
            super(c, id, pinLength, h);
        }

        @Override
        protected void prepare() {
            threads = Runtime.getRuntime().availableProcessors();
            System.out.println("[Worker " + workerId + "] потоков: " + threads
                    + ", PIN: " + pinLength + " знаков");
            pool = Executors.newFixedThreadPool(threads);

            // Мониторинг + heartbeat + STOP
            monitor = Executors.newScheduledThreadPool(2);
            monitor.scheduleAtFixedRate(() -> client.reportProgress(workerId, totalChecked.get()),
                    2, 2, TimeUnit.SECONDS);
            monitor.scheduleAtFixedRate(() -> client.heartbeat(workerId),
                    3, 3, TimeUnit.SECONDS);
            monitor.scheduleAtFixedRate(() -> {
                if (!found.get()) {
                    var st = client.getStatus();
                    if (Boolean.TRUE.equals(st.get("stop"))) {
                        System.out.println("[Worker " + workerId + "] STOP от сервера. PIN="
                                + st.get("foundPin"));
                        found.set(true);
                    }
                }
            }, 2, 2, TimeUnit.SECONDS);
        }

        @Override
        protected long[] requestRange() {
            // Первый диапазон — /api/register
            try {
                Map<String, Object> reg = client.register(workerId);
                if (Boolean.TRUE.equals(reg.get("stop")) || reg.get("start") == null) return null;
                long start = ((Number) reg.get("start")).longValue();
                long end = ((Number) reg.get("end")).longValue();
                System.out.printf("[Worker %s] диапазон %s–%s%n",
                        workerId, fmt(start), fmt(end));
                return new long[]{start, end};
            } catch (Exception e) {
                System.err.println("[Worker " + workerId + "] register failed: " + e.getMessage());
                return null;
            }
        }

        @Override
        protected void iterate(long start, long end) throws Exception {
            queue = new LinkedBlockingQueue<>(2000);

            // PRODUCER
            Thread producer = new Thread(() -> {
                try {
                    for (long i = start; i <= end && !found.get(); i++) {
                        queue.put(fmt(i));
                    }
                    queue.put(POISON);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            producer.start();

            // CONSUMERS
            CountDownLatch latch = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try { consume(); } finally { latch.countDown(); }
                });
            }
            latch.await();

            // После окончания диапазона — просим следующий у сервера
            if (!found.get()) {
                Map<String, Object> next = client.nextRange(workerId);
                if (Boolean.TRUE.equals(next.get("stop")) || next.get("start") == null) {
                    System.out.println("[Worker " + workerId + "] Работа закончилась.");
                    return;
                }
                long ns = ((Number) next.get("start")).longValue();
                long ne = ((Number) next.get("end")).longValue();
                System.out.printf("[Worker %s] Новый динамический диапазон %s–%s%n",
                        workerId, fmt(ns), fmt(ne));
                extraRanges.add(new long[]{ns, ne});
            }

            // Забираем следующий диапазон из очереди (если есть)
            if (!extraRanges.isEmpty()) {
                long[] r = extraRanges.poll();
                iterate(r[0], r[1]);
            }
        }

        private void consume() {
            try {
                while (!found.get()) {
                    String pin = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (pin == null) {
                        if (queue.isEmpty() && !extraRanges.isEmpty()) return; // диапазон кончился
                        continue;
                    }
                    if (POISON.equals(pin)) {
                        queue.put(POISON);
                        return;
                    }
                    totalChecked.incrementAndGet();
                    if (md5(pin).equals(targetHash)) {
                        if (found.compareAndSet(false, true)) {
                            System.out.println("[Worker " + workerId + "] НАЙДЕНО: " + pin);
                            client.reportResult(workerId, pin, targetHash);
                        }
                        return;
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @Override
        protected void onFound() {
            found.set(true);
            if (pool != null) pool.shutdownNow();
            if (monitor != null) monitor.shutdownNow();
            System.out.println("[Worker " + workerId + "] завершено, проверено: " + totalChecked.get());
        }

        private String fmt(long n) {
            int len = pinLength;
            return String.format("%0" + len + "d", n);
        }
    }

    // ============ MAIN ============
    public static void main(String[] args) throws Exception {
        String serverUrl = System.getenv().getOrDefault("SERVER_URL", "http://localhost:8080");
        String workerId  = System.getenv().getOrDefault("WORKER_ID",
                "worker-" + System.currentTimeMillis());
        if (args.length >= 1) serverUrl = args[0];
        if (args.length >= 2) workerId = args[1];

        System.out.println("[Worker] id=" + workerId + " server=" + serverUrl);
        var client = new ServerClient(serverUrl);

        // Регистрируемся — получаем pinLength и первый диапазон
        Map<String, Object> reg = client.register(workerId);
        if (Boolean.TRUE.equals(reg.get("stop")) || reg.get("start") == null) {
            System.out.println("[Worker] Нет диапазона / STOP. Выход.");
            return;
        }
        int pinLength = ((Number) reg.getOrDefault("pinLength", 4)).intValue();
        String hash = (String) reg.get("targetHash");

        new Md5BruteForcer(client, workerId, pinLength, hash).run();
    }
}