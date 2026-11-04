package dev.jaderss.lvr.mvc;

import static java.util.Map.entry;

import com.sun.management.OperatingSystemMXBean;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController {

    private static final Path STATUS = Path.of("/proc/self/status");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final OperatingSystemMXBean OS =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private final boolean virtualThreads;
    private final HikariDataSource pool;

    StatsController(@Value("${spring.threads.virtual.enabled}") boolean virtualThreads, HikariDataSource pool) {
        this.virtualThreads = virtualThreads;
        this.pool = pool;
    }

    @GetMapping("/health")
    String health() {
        return "ok";
    }

    @GetMapping("/stats")
    Map<String, Object> stats() throws IOException {
        boolean linux = Files.isReadable(STATUS);
        return Map.ofEntries(
                entry("app", virtualThreads ? "mvc-vt" : "mvc-platform"),
                entry("lang", "java"),
                entry("version", System.getProperty("java.version")),
                entry("mode", virtualThreads ? "virtual" : "platform"),
                entry("cpus", Runtime.getRuntime().availableProcessors()),
                entry("pool_size", pool.getMaximumPoolSize()),
                entry("rss_mb", linux ? residentMegabytes(Files.readString(STATUS)) : 0.0),
                entry("cpu_seconds", linux ? OS.getProcessCpuTime() / 1e9 : 0.0));
    }

    static double residentMegabytes(String status) {
        return status.lines()
                .filter(line -> line.startsWith("VmRSS:"))
                .mapToDouble(line -> Long.parseLong(SPACES.split(line)[1]) / 1024.0)
                .findFirst()
                .orElse(0);
    }
}
