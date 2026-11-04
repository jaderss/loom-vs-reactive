package dev.jaderss.lvr.webflux;

import static java.util.Map.entry;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController {

    private static final Path STATUS = Path.of("/proc/self/status");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final OperatingSystemMXBean OS =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private final int poolSize;

    StatsController(R2dbcProperties r2dbc) {
        this.poolSize = r2dbc.getPool().getMaxSize();
    }

    @GetMapping("/health")
    String health() {
        return "ok";
    }

    @GetMapping("/stats")
    Map<String, Object> stats() throws IOException {
        boolean linux = Files.isReadable(STATUS);
        return Map.ofEntries(
                entry("app", "webflux"),
                entry("lang", "java"),
                entry("version", System.getProperty("java.version")),
                entry("mode", "reactor"),
                entry("cpus", Runtime.getRuntime().availableProcessors()),
                entry("pool_size", poolSize),
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
