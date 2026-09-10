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
        private final int threshold;
        private final long timeoutMs;
        private int failures = 0;
        private long openedAt = 0;
        private State state = State.CLOSED;

        CircuitBreaker(int t, long to) { threshold = t; timeoutMs = to; }

        synchronized boolean allow() {
            if (state == State.CLOSED) return true;
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - openedAt > timeoutMs) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            }
            return true;
        }

        synchronized void success() {
            failures = 0;
            state = State.CLOSED;
        }

        synchronized void failure() {
            if (++failures >= threshold) {
                state = State.OPEN;
                openedAt = System.currentTimeMillis();
            }
        }
    }

    // ============ HTTP CLIENT ============
    static class ServerClient {
        private final String base;
        private final HttpClient http;
        private final ObjectMapper mapper = new ObjectMapper();
        private final CircuitBreaker breaker = new CircuitBreaker(3, 5000);

        ServerClient(String base) {
            this.base = base;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
        }

        Map<String, Object> register(String id) throws Exception {
            return post("/api/register", Map.of("workerId", id));
        }

        void reportResult(String id, String pin) {
            try {
                post("/api/result", Map.of("workerId", id, "pin", pin));
            } catch (Exception e) {
                System.err.println("result err: " + e.getMessage());
            }
        }

        void reportProgress(String id, long checked) {
            try {
                post("/api/progress", Map.of("workerId", id, "checked", checked));
            } catch (Exception ignored) {}
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

        @SuppressWarnings("unchecked")
        private Map<String, Object> post(String path, Map<String, Object> body)
                throws Exception {
            var req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body)))
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            return mapper.readValue(resp, Map.class);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> get(String path) throws Exception {
            var req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            return mapper.readValue(resp, Map.class);
        }
    }

    // ============ TEMPLATE METHOD ============
    static abstract class AbstractBruteForcer {
        protected final ServerClient client;
        protected final String workerId;
        protected final int start, end;
        protected final String targetHash;
        protected final AtomicBoolean found = new AtomicBoolean(false);

        AbstractBruteForcer(ServerClient c, String id, int s, int e, String h) {
            client = c; workerId = id; start = s; end = e; targetHash = h;
        }

        public final void run() throws Exception {
            prepare();
            iterate();
            onFound();
        }

        protected abstract void prepare();
        protected abstract void iterate() throws Exception;
        protected abstract void onFound();

        protected static String md5(String s) {
            try {
                var md = MessageDigest.getInstance("MD5");
                var d = md.digest(s.getBytes(StandardCharsets.UTF_8));
                var sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ============ CONCRETE BRUTE FORCER ============
    static class Md5BruteForcer extends AbstractBruteForcer {
        private static final String POISON = "__END__";
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(1000);
        private final AtomicLong checked = new AtomicLong(0);
        private ExecutorService pool;
        private ScheduledExecutorService monitor;

        Md5BruteForcer(ServerClient c, String id, int s, int e, String h) {
            super(c, id, s, e, h);
        }

        @Override
        protected void prepare() {
            int threads = Runtime.getRuntime().availableProcessors();
            System.out.println("[Worker " + workerId + "] потоков: " + threads
                    + ", диапазон " + start + "–" + end);
            pool = Executors.newFixedThreadPool(threads);

            // PRODUCER
            pool.submit(() -> {
                try {
                    for (int i = start; i <= end && !found.get(); i++)
                        queue.put(String.format("%04d", i));
                    queue.put(POISON);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // CONSUMERS
            for (int i = 0; i < threads; i++) pool.submit(this::consume);

            // Мониторинг + STOP от сервера
            monitor = Executors.newSingleThreadScheduledExecutor();
            monitor.scheduleAtFixedRate(() -> {
                client.reportProgress(workerId, checked.get());
                if (!found.get()) {
                    var st = client.getStatus();
                    if (Boolean.TRUE.equals(st.get("stop"))) {
                        System.out.println("[Worker " + workerId
                                + "] STOP от сервера. PIN=" + st.get("foundPin"));
                        found.set(true);
                    }
                }
            }, 2, 2, TimeUnit.SECONDS);
        }

        private void consume() {
            try {
                while (!found.get()) {
                    String pin = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (pin == null) {
                        if (pool.isShutdown()) return;
                        continue;
                    }
                    if (POISON.equals(pin)) {
                        queue.put(POISON);
                        return;
                    }
                    checked.incrementAndGet();
                    if (md5(pin).equals(targetHash)) {
                        if (found.compareAndSet(false, true)) {
                            System.out.println("[Worker " + workerId
                                    + "] НАЙДЕНО: " + pin);
                            client.reportResult(workerId, pin);
                        }
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        protected void iterate() throws Exception {
            while (!found.get()) {
                if (pool.isTerminated()) break;
                Thread.sleep(150);
                if (checked.get() >= (end - start + 1)) break;
            }
        }

        @Override
        protected void onFound() {
            found.set(true);
            if (pool != null) pool.shutdownNow();
            if (monitor != null) monitor.shutdownNow();
            System.out.println("[Worker " + workerId
                    + "] завершено, проверено: " + checked.get());
        }
    }

    // ============ MAIN ============
    public static void main(String[] args) throws Exception {
        String serverUrl = System.getenv().getOrDefault(
                "SERVER_URL", "http://localhost:8080");
        String workerId = System.getenv().getOrDefault(
                "WORKER_ID", "worker-" + System.currentTimeMillis());

        // Аргументы командной строки имеют приоритет над env
        if (args.length >= 1) serverUrl = args[0];
        if (args.length >= 2) workerId = args[1];

        System.out.println("[Worker] id=" + workerId + " server=" + serverUrl);
        var client = new ServerClient(serverUrl);
        var reg = client.register(workerId);

        if (Boolean.TRUE.equals(reg.get("stop")) || reg.get("start") == null) {
            System.out.println("[Worker] Нет диапазона / STOP. Выход.");
            return;
        }

        int start = ((Number) reg.get("start")).intValue();
        int end = ((Number) reg.get("end")).intValue();
        String hash = (String) reg.get("targetHash");
        System.out.printf("[Worker] диапазон %04d–%04d, hash=%s%n",
                start, end, hash);

        new Md5BruteForcer(client, workerId, start, end, hash).run();
    }
}