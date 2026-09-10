package app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("*");
            }
        };
    }

    // ============ SINGLETON: TaskRegistry ============
    @org.springframework.stereotype.Component
    public static class TaskRegistry {
        private final AtomicReference<String> targetHash =
                new AtomicReference<>("81dc9bdb52d04dc20036dbd8313ed055"); // 1234
        private final AtomicReference<String> foundPin = new AtomicReference<>(null);
        private final AtomicReference<Boolean> stop = new AtomicReference<>(false);
        private final Map<String, Map<String, Object>> workers = new ConcurrentHashMap<>();

        public String getTargetHash() { return targetHash.get(); }

        public void setTargetHash(String h) {
            targetHash.set(h);
            foundPin.set(null);
            stop.set(false);
        }

        public String getFoundPin() { return foundPin.get(); }
        public boolean isStop() { return stop.get(); }

        public boolean trySetFoundPin(String pin) {
            boolean ok = foundPin.compareAndSet(null, pin);
            if (ok) stop.set(true);
            return ok;
        }

        public void assignRange(String id, int start, int end) {
            workers.put(id, new ConcurrentHashMap<>(Map.of(
                    "id", id, "start", start, "end", end,
                    "checked", 0L, "status", "RUNNING")));
        }

        public void updateProgress(String id, long checked) {
            var w = workers.get(id);
            if (w != null) w.put("checked", checked);
        }

        public void setStatus(String id, String status) {
            var w = workers.get(id);
            if (w != null) w.put("status", status);
        }

        public Collection<Map<String, Object>> getWorkers() {
            return workers.values();
        }
    }

    // ============ STRATEGY: SplitStrategy ============
    public interface SplitStrategy {
        List<int[]> split(int total, int parts);
    }

    public static class EqualSplitStrategy implements SplitStrategy {
        @Override
        public List<int[]> split(int total, int parts) {
            List<int[]> res = new ArrayList<>();
            int size = total / parts, rem = total % parts, start = 0;
            for (int i = 0; i < parts; i++) {
                int end = start + size - 1 + (i < rem ? 1 : 0);
                res.add(new int[]{start, end});
                start = end + 1;
            }
            return res;
        }
    }

    public static class WeightedSplitStrategy implements SplitStrategy {
        private final double[] weights;
        public WeightedSplitStrategy(double[] w) { this.weights = w; }

        @Override
        public List<int[]> split(int total, int parts) {
            double sum = Arrays.stream(weights).sum();
            List<int[]> res = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < parts; i++) {
                int size = (int) Math.round(total * weights[i] / sum);
                int end = Math.min(start + size - 1, total - 1);
                res.add(new int[]{start, end});
                start = end + 1;
            }
            if (start < total) {
                res.set(res.size() - 1,
                        new int[]{res.get(res.size() - 1)[0], total - 1});
            }
            return res;
        }
    }

    // ============ RangeDistributor ============
    @org.springframework.stereotype.Component
    public static class RangeDistributor {
        private SplitStrategy strategy = new EqualSplitStrategy();
        private List<int[]> ranges;
        private final AtomicInteger next = new AtomicInteger(0);

        public synchronized void init(int total, int parts) {
            this.ranges = strategy.split(total, parts);
            this.next.set(0);
        }

        public synchronized int[] nextRange() {
            int i = next.getAndIncrement();
            return (ranges == null || i >= ranges.size()) ? null : ranges.get(i);
        }

        public List<int[]> getAllRanges() { return ranges; }
    }

    // ============ OBSERVER: WorkerNotifier ============
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

    // ============ FACTORY METHOD: WorkerFactory ============
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
        private final int total, parts;
        public StartCommand(RangeDistributor d, int total, int parts) {
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
        private final int[] range;
        private boolean result;
        public CheckRangeCommand(RangeDistributor d, int[] r) { this.d = d; this.range = r; }
        @Override public void execute() {
            result = d.getAllRanges() != null && d.getAllRanges().stream()
                    .anyMatch(x -> x[0] == range[0] && x[1] == range[1]);
        }
        public boolean getResult() { return result; }
    }

    // ============ CONTROLLER ============
    @RestController
    @RequestMapping("/api")
    public static class TaskController {
        private final TaskRegistry registry;
        private final RangeDistributor distributor;
        private final WorkerNotifier notifier;
        private final WorkerFactory factory;

        public TaskController(TaskRegistry r, RangeDistributor d,
                              WorkerNotifier n, WorkerFactory f) {
            this.registry = r;
            this.distributor = d;
            this.notifier = n;
            this.factory = f;
            new StartCommand(d, 10000, 3).execute();
        }

        @PostMapping("/task")
        public Map<String, Object> setTask(@RequestBody Map<String, String> body) {
            registry.setTargetHash(body.get("hash"));
            new StartCommand(distributor, 10000, 3).execute();
            return Map.of("status", "OK", "hash", body.get("hash"));
        }

        @PostMapping("/register")
        public Map<String, Object> register(@RequestBody Map<String, String> body) {
            String id = body.get("workerId");
            int[] r = distributor.nextRange();
            if (r == null) {
                return Map.of("workerId", id, "stop", true,
                        "targetHash", registry.getTargetHash());
            }
            registry.assignRange(id, r[0], r[1]);
            var w = factory.create("remote", id);
            if (w instanceof WorkerNotifier.Observer o) notifier.register(o);
            return Map.of("workerId", id, "start", r[0], "end", r[1],
                    "targetHash", registry.getTargetHash(), "stop", false);
        }

        @PostMapping("/result")
        public Map<String, Object> result(@RequestBody Map<String, String> body) {
            boolean first = registry.trySetFoundPin(body.get("pin"));
            if (first) notifier.notifyAll(body.get("pin"));
            return Map.of("accepted", first);
        }

        @GetMapping("/status")
        public Map<String, Object> status() {
            return Map.of(
                    "stop", registry.isStop(),
                    "foundPin", registry.getFoundPin() == null ? "" : registry.getFoundPin(),
                    "targetHash", registry.getTargetHash());
        }

        @PostMapping("/progress")
        public Map<String, Object> progress(@RequestBody Map<String, Object> body) {
            registry.updateProgress((String) body.get("workerId"),
                    ((Number) body.get("checked")).longValue());
            return Map.of("status", "OK");
        }

        @GetMapping("/summary")
        public Map<String, Object> summary() {
            Map<String, Object> m = new HashMap<>();
            m.put("targetHash", registry.getTargetHash());
            m.put("foundPin", registry.getFoundPin());
            m.put("stop", registry.isStop());
            m.put("workers", registry.getWorkers());
            return m;
        }
    }
}