package dev.jaderss.lvr.mvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController {

    private static final Path STATUS = Path.of("/proc/self/status");
    private static final Path STAT = Path.of("/proc/self/stat");

    private final boolean virtualThreads;
    private final int poolSize;

    StatsController(@Value("${spring.threads.virtual.enabled}") boolean virtualThreads, BenchProperties properties) {
        this.virtualThreads = virtualThreads;
        this.poolSize = properties.poolSize();
    }

    @GetMapping("/health")
    String health() {
        return "ok";
    }

    @GetMapping("/stats")
    Map<String, Object> stats() {
        boolean linux = Files.isReadable(STAT);
        return Map.of(
                "app",
                virtualThreads ? "mvc-vt" : "mvc-platform",
                "lang",
                "java",
                "version",
                System.getProperty("java.version"),
                "mode",
                virtualThreads ? "virtual" : "platform",
                "cpus",
                Runtime.getRuntime().availableProcessors(),
                "pool_size",
                poolSize,
                "rss_mb",
                linux ? residentMegabytes(read(STATUS)) : 0.0,
                "cpu_seconds",
                linux ? cpuSeconds(read(STAT)) : 0.0);
    }

    static double residentMegabytes(String status) {
        return status.lines()
                .filter(line -> line.startsWith("VmRSS:"))
                .mapToDouble(line -> Long.parseLong(line.split("\\s+")[1]) / 1024.0)
                .findFirst()
                .orElse(0);
    }

    static double cpuSeconds(String stat) {
        List<String> fields =
                List.of(stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+"));
        return (Long.parseLong(fields.get(11)) + Long.parseLong(fields.get(12))) / 100.0;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
