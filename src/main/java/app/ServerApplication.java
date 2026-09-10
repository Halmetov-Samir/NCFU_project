package app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@SpringBootApplication
public class ServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

    // ============ CORS ============
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**").allowedOrigins("*").allowedMethods("*");
            }
        };
    }

    // ============ RATE LIMITING FILTER ============
    @Configuration
    public static class RateLimitConfig {
        @Bean
        public FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
            FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>();
            reg.setFilter(new RateLimitFilter());
            reg.addUrlPatterns("/api/*");
            reg.setOrder(1);
            return reg;
        }
    }

    /** RATE LIMITING — token bucket на 200 запросов/сек на IP. */
    public static class RateLimitFilter implements Filter {
        private static final int CAPACITY = 200;
        private static final long REFILL_PERIOD_MS = 1000;
        private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

        static class Bucket {
            AtomicInteger tokens = new AtomicInteger(CAPACITY);
            AtomicLong lastRefill = new AtomicLong(System.currentTimeMillis());
        }

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) res;
            String ip = request.getRemoteAddr();

            Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket());
            long now = System.currentTimeMillis();
            long last = b.lastRefill.get();
            if (now - last > REFILL_PERIOD_MS) {
                if (b.lastRefill.compareAndSet(last, now)) {
                    b.tokens.set(CAPACITY);
                }
            }
            if (b.tokens.decrementAndGet() < 0) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
                return;
            }
            chain.doFilter(req, res);
        }
    }

    // ============ SINGLETON: TaskRegistry ============
    @org.springframework.stereotype.Component
    public static class TaskRegistry {

        /** Длина PIN: 4, 6 или 8 знаков. */
        private final AtomicInteger pinLength = new AtomicInteger(4);

        /** Целевой хэш — по умолчанию MD5 от "1234". */
        private final AtomicReference<String> targetHash =
                new AtomicReference<>("81dc9bdb52d04dc20036dbd8313ed055");

        private final AtomicReference<String> foundPin = new AtomicReference<>(null);
        private final AtomicReference<Boolean> stop = new AtomicReference<>(false);
        private final Map<String, Map<String, Object>> workers = new ConcurrentHashMap<>();
        private final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();

        /** История попыток (последние 500). */
        private final Deque<Map<String, Object>> history = new ConcurrentLinkedDeque<>();
        private static final int HISTORY_MAX = 500;

        /** Сэмплы скорости: {timestamp, totalChecked}. */
        private final Deque<long[]> speedSamples = new ConcurrentLinkedDeque<>();
        private static final int SPEED_MAX = 60;

        public int getPinLength() { return pinLength.get(); }

        public void setPinLength(int len) {
            if (len != 4 && len != 6 && len != 8)
                throw new IllegalArgumentException("pinLength must be 4, 6 or 8");
            pinLength.set(len);
            reset();
        }

        public long getRangeMax() {
            return (long) Math.pow(10, pinLength.get());
        }

        public String getTargetHash() { return targetHash.get(); }
        public void setTargetHash(String h) { targetHash.set(h); }

        public String getFoundPin() { return foundPin.get(); }
        public boolean isStop() { return stop.get(); }

        public void reset() {
            foundPin.set(null);
            stop.set(false);
            workers.clear();
            lastHeartbeat.clear();
            history.clear();
            speedSamples.clear();
        }

        public boolean trySetFoundPin(String pin) {
            boolean ok = foundPin.compareAndSet(null, pin);
            if (ok) stop.set(true);
            return ok;
        }

        public void assignRange(String id, long start, long end, boolean initial) {
            if (initial) {
                Map<String, Object> w = new ConcurrentHashMap<>();
                w.put("id", id);
                w.put("start", start);
                w.put("end", end);
                w.put("checked", 0L);
                w.put("status", "RUNNING");
                workers.put(id, w);
            } else {
                var w = workers.get(id);
                if (w != null) {
                    w.put("start", start);
                    w.put("end", end);
                    w.put("status", "RUNNING");
                }
            }
            lastHeartbeat.put(id, System.currentTimeMillis());
        }

        public void updateProgress(String id, long checked) {
            var w = workers.get(id);
            if (w != null) w.put("checked", checked);
        }

        public void addHistory(String workerId, String pin, String hash) {
            Map<String, Object> h = new HashMap<>();
            h.put("workerId", workerId);
            h.put("pin", pin);
            h.put("hash", hash);
            h.put("time", System.currentTimeMillis());
            history.addFirst(h);
            while (history.size() > HISTORY_MAX) history.pollLast();
        }

        public List<Map<String, Object>> getHistory(int limit) {
            return history.stream().limit(limit).toList();
        }

        public void recordHeartbeat(String id) {
            lastHeartbeat.put(id, System.currentTimeMillis());
        }

        public void removeWorker(String id) {
            workers.remove(id);
            lastHeartbeat.remove(id);
        }

        public Collection<Map<String, Object>> getWorkers() { return workers.values(); }
        public Set<String> getWorkerIds() { return workers.keySet(); }
        public Map<String, Long> getLastHeartbeats() { return lastHeartbeat; }

        public void addSpeedSample(long totalChecked) {
            speedSamples.addLast(new long[]{System.currentTimeMillis(), totalChecked});
            while (speedSamples.size() > SPEED_MAX) speedSamples.pollFirst();
        }

        public List<long[]> getSpeedSamples() { return new ArrayList<>(speedSamples); }

        public List<String> findDeadWorkers(long timeoutMs) {
            long now = System.currentTimeMillis();
            List<String> dead = new ArrayList<>();
            for (var e : lastHeartbeat.entrySet()) {
                if (now - e.getValue() > timeoutMs) dead.add(e.getKey());
            }
            return dead;
        }
    }

    // ============ STRATEGY: SplitStrategy ============
    public interface SplitStrategy {
        List<long[]> split(long total, int parts);
    }

    public static class EqualSplitStrategy implements SplitStrategy {
        @Override public List<long[]> split(long total, int parts) {
            List<long[]> res = new ArrayList<>();
            long size = total / parts, rem = total % parts, start = 0;
            for (int i = 0; i < parts; i++) {
                long end = start + size - 1 + (i < rem ? 1 : 0);
                res.add(new long[]{start, end});
                start = end + 1;
            }
            return res;
        }
    }

    // ============ RangeDistributor ============
    @org.springframework.stereotype.Component
    public static class RangeDistributor {
        private SplitStrategy strategy = new EqualSplitStrategy();
        private List<long[]> ranges;
        private final AtomicInteger next = new AtomicInteger(0);
        private final AtomicLong extraCursor = new AtomicLong(0);

        public synchronized void init(long total, int parts) {
            this.ranges = strategy.split(total, parts);
            this.next.set(0);
            this.extraCursor.set(0);
        }

        public synchronized long[] nextRange() {
            int i = next.getAndIncrement();
            return (ranges == null || i >= ranges.size()) ? null : ranges.get(i);
        }

        public List<long[]> getAllRanges() { return ranges; }

        /**
         * Динамическое перераспределение — выдаём следующий свободный чанк.
         * Делим всё пространство на 20 частей, отдаём по одной.
         */
        public synchronized long[] nextDynamicChunk() {
            long total = ranges != null && !ranges.isEmpty()
                    ? ranges.get(ranges.size() - 1)[1] + 1 : 0;
            if (total == 0) return null;
            int chunks = 20;
            long size = total / chunks;
            long idx = extraCursor.getAndIncrement();
            if (idx >= chunks) return null;
            long start = idx * size;
            long end = (idx == chunks - 1) ? total - 1 : start + size - 1;
            return new long[]{start, end};
        }

        public synchronized void resetDynamic() {
            extraCursor.set(0);
        }
    }

    // ============ OBSERVER ============
    @org.springframework.stereotype.Component
    public static class WorkerNotifier {
        public interface Observer {
            void onSolutionFound(String pin);
            String getId();
        }
        private final List<Observer> observers = new CopyOnWriteArrayList<>();
        public void register(Observer o) { observers.add(o); }
        public void notifyAll(String pin) {
            for (var o : observers) {
                try { o.onSolutionFound(pin); } catch (Exception ignored) {}
            }
        }
    }

    // ============ FACTORY METHOD ============
    @org.springframework.stereotype.Component
    public static class WorkerFactory {
        public interface Worker {
            String getId();
            void stop(String pin);
        }

        public static class RemoteWorker implements Worker, WorkerNotifier.Observer {
            private final String id;
            private volatile String stopPin;
            public RemoteWorker(String id) { this.id = id; }
            @Override public String getId() { return id; }
            @Override public void stop(String pin) { this.stopPin = pin; }
            @Override public void onSolutionFound(String pin) { stop(pin); }
        }

        public Worker create(String type, String id) {
            return new RemoteWorker(id);
        }
    }

    // ============ COMMAND ============
    public interface Command { void execute(); }

    public static class StartCommand implements Command {
        private final RangeDistributor d;
        private final long total;
        private final int parts;
        public StartCommand(RangeDistributor d, long total, int parts) {
            this.d = d; this.total = total; this.parts = parts;
        }
        @Override public void execute() { d.init(total, parts); }
    }

    public static class StopCommand implements Command {
        private final TaskRegistry r;
        private final String pin;
        public StopCommand(TaskRegistry r, String pin) { this.r = r; this.pin = pin; }
        @Override public void execute() { r.trySetFoundPin(pin); }
    }

    public static class CheckRangeCommand implements Command {
        private final RangeDistributor d;
        private final long[] range;
        private boolean result;
        public CheckRangeCommand(RangeDistributor d, long[] r) { this.d = d; this.range = r; }
        @Override public void execute() {
            result = d.getAllRanges() != null && d.getAllRanges().stream()
                    .anyMatch(x -> x[0] == range[0] && x[1] == range[1]);
        }
        public boolean getResult() { return result; }
    }

    // ============ SSE (Server-Sent Events) ============
    @org.springframework.stereotype.Component
    public static class EventBroadcaster {
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

        public SseEmitter subscribe() {
            SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
            emitters.add(emitter);
            emitter.onCompletion(() -> emitters.remove(emitter));
            emitter.onTimeout(() -> emitters.remove(emitter));
            emitter.onError(e -> emitters.remove(emitter));
            return emitter;
        }

        public void broadcast(String event, Object data) {
            for (SseEmitter e : emitters) {
                try {
                    e.send(SseEmitter.event().name(event).data(data));
                } catch (Exception ex) {
                    emitters.remove(e);
                }
            }
        }
    }

    /** Периодические задачи: сэмплы скорости, broadcast, heartbeat-проверка. */
    @org.springframework.stereotype.Component
    public static class BackgroundTasks {
        private final TaskRegistry registry;
        private final EventBroadcaster broadcaster;

        public BackgroundTasks(TaskRegistry r, EventBroadcaster b) {
            this.registry = r;
            this.broadcaster = b;
            ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);

            // Сэмпл скорости + broadcast раз в секунду
            exec.scheduleAtFixedRate(() -> {
                long total = registry.getWorkers().stream()
                        .mapToLong(w -> ((Number) w.getOrDefault("checked", 0L)).longValue())
                        .sum();
                registry.addSpeedSample(total);
                broadcaster.broadcast("update", buildSummary());
            }, 1, 1, TimeUnit.SECONDS);

            // Heartbeat: выкидываем мёртвых воркеров (нет сигнала > 15 сек)
            exec.scheduleAtFixedRate(() -> {
                for (String dead : registry.findDeadWorkers(15_000)) {
                    registry.removeWorker(dead);
                    broadcaster.broadcast("worker-dead", Map.of("id", dead));
                }
            }, 5, 5, TimeUnit.SECONDS);
        }

        private Map<String, Object> buildSummary() {
            Map<String, Object> m = new HashMap<>();
            m.put("targetHash", registry.getTargetHash());
            m.put("foundPin", registry.getFoundPin());
            m.put("stop", registry.isStop());
            m.put("pinLength", registry.getPinLength());
            m.put("rangeMax", registry.getRangeMax());
            m.put("workers", registry.getWorkers());
            m.put("speed", registry.getSpeedSamples());
            return m;
        }
    }

    // ============ CONTROLLER ============
    @RestController
    @RequestMapping("/api")
    public static class TaskController {
        private final TaskRegistry registry;
        private final RangeDistributor distributor;
        private final WorkerNotifier notifier;
        private final WorkerFactory factory;
        private final EventBroadcaster broadcaster;

        public TaskController(TaskRegistry r, RangeDistributor d,
                              WorkerNotifier n, WorkerFactory f,
                              EventBroadcaster b) {
            this.registry = r;
            this.distributor = d;
            this.notifier = n;
            this.factory = f;
            this.broadcaster = b;
            initRanges(registry.getPinLength());
        }

        private void initRanges(int pinLength) {
            long total = (long) Math.pow(10, pinLength);
            new StartCommand(distributor, total, 3).execute();
            distributor.resetDynamic();
        }

        @PostMapping("/task")
        public Map<String, Object> setTask(@RequestBody Map<String, String> body) {
            String hash = body.get("hash");
            String lenStr = body.getOrDefault("pinLength", "4");
            int pinLength;
            try {
                pinLength = Integer.parseInt(lenStr);
            } catch (NumberFormatException e) {
                pinLength = 4;
            }
            registry.setPinLength(pinLength);
            registry.setTargetHash(hash);
            initRanges(pinLength);
            broadcaster.broadcast("reset", Map.of("pinLength", pinLength));
            return Map.of("status", "OK", "hash", hash, "pinLength", pinLength);
        }

        @PostMapping("/register")
        public Map<String, Object> register(@RequestBody Map<String, String> body) {
            String id = body.get("workerId");
            long[] r = distributor.nextRange();
            if (r == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("workerId", id);
                resp.put("stop", true);
                resp.put("targetHash", registry.getTargetHash());
                resp.put("pinLength", registry.getPinLength());
                return resp;
            }
            registry.assignRange(id, r[0], r[1], true);
            var w = factory.create("remote", id);
            if (w instanceof WorkerNotifier.Observer o) notifier.register(o);

            Map<String, Object> resp = new HashMap<>();
            resp.put("workerId", id);
            resp.put("start", r[0]);
            resp.put("end", r[1]);
            resp.put("targetHash", registry.getTargetHash());
            resp.put("pinLength", registry.getPinLength());
            resp.put("stop", false);
            return resp;
        }

        /** Динамическое перераспределение — воркер просит ещё работы. */
        @PostMapping("/next-range")
        public Map<String, Object> nextRange(@RequestBody Map<String, String> body) {
            if (registry.isStop() || registry.getFoundPin() != null) {
                return Map.of("stop", true, "reason", "solved");
            }
            long[] r = distributor.nextDynamicChunk();
            if (r == null) {
                return Map.of("stop", true, "reason", "no more work");
            }
            String id = body.get("workerId");
            registry.assignRange(id, r[0], r[1], false);
            Map<String, Object> resp = new HashMap<>();
            resp.put("start", r[0]);
            resp.put("end", r[1]);
            resp.put("stop", false);
            return resp;
        }

        @PostMapping("/result")
        public Map<String, Object> result(@RequestBody Map<String, String> body) {
            String pin = body.get("pin");
            String hash = body.getOrDefault("hash", "");
            String workerId = body.getOrDefault("workerId", "unknown");
            registry.addHistory(workerId, pin, hash);
            boolean first = registry.trySetFoundPin(pin);
            if (first) {
                notifier.notifyAll(pin);
                broadcaster.broadcast("found", Map.of("pin", pin, "workerId", workerId));
            }
            return Map.of("accepted", first);
        }

        @GetMapping("/status")
        public Map<String, Object> status() {
            Map<String, Object> m = new HashMap<>();
            m.put("stop", registry.isStop());
            m.put("foundPin", registry.getFoundPin() == null ? "" : registry.getFoundPin());
            m.put("targetHash", registry.getTargetHash());
            m.put("pinLength", registry.getPinLength());
            return m;
        }

        @PostMapping("/progress")
        public Map<String, Object> progress(@RequestBody Map<String, Object> body) {
            String id = (String) body.get("workerId");
            registry.updateProgress(id, ((Number) body.get("checked")).longValue());
            registry.recordHeartbeat(id);
            return Map.of("status", "OK");
        }

        @PostMapping("/heartbeat")
        public Map<String, Object> heartbeat(@RequestBody Map<String, String> body) {
            registry.recordHeartbeat(body.get("workerId"));
            return Map.of("status", "OK");
        }

        @GetMapping("/summary")
        public Map<String, Object> summary() {
            Map<String, Object> m = new HashMap<>();
            m.put("targetHash", registry.getTargetHash());
            m.put("foundPin", registry.getFoundPin());
            m.put("stop", registry.isStop());
            m.put("pinLength", registry.getPinLength());
            m.put("rangeMax", registry.getRangeMax());
            m.put("workers", registry.getWorkers());
            m.put("speed", registry.getSpeedSamples());
            return m;
        }

        @GetMapping("/history")
        public List<Map<String, Object>> history(
                @RequestParam(defaultValue = "50") int limit) {
            return registry.getHistory(limit);
        }

        /** SSE — real-time-обновления клиента. */
        @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter stream() {
            SseEmitter emitter = broadcaster.subscribe();
            try {
                emitter.send(SseEmitter.event().name("hello")
                        .data(Map.of("status", "connected")));
            } catch (IOException ignored) {}
            return emitter;
        }
    }
}