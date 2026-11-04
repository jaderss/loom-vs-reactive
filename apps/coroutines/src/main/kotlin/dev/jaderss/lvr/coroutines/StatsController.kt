package dev.jaderss.lvr.coroutines

import com.sun.management.OperatingSystemMXBean
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

private val STATUS = Path.of("/proc/self/status")
private val SPACES = Regex("\\s+")
private val OS = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean

@RestController
class StatsController(
    private val r2dbc: R2dbcProperties,
) {
    @GetMapping("/health")
    fun health() = "ok"

    @GetMapping("/stats")
    fun stats(): Map<String, Any> {
        val linux = Files.isReadable(STATUS)
        return mapOf(
            "app" to "coroutines",
            "lang" to "kotlin",
            "version" to "${KotlinVersion.CURRENT}-java${System.getProperty("java.version")}",
            "mode" to "coroutines",
            "cpus" to Runtime.getRuntime().availableProcessors(),
            "pool_size" to r2dbc.pool.maxSize,
            "rss_mb" to if (linux) residentMegabytes(Files.readString(STATUS)) else 0.0,
            "cpu_seconds" to if (linux) OS.processCpuTime / 1e9 else 0.0,
        )
    }
}

fun residentMegabytes(status: String): Double =
    status
        .lineSequence()
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.split(SPACES)
        ?.get(1)
        ?.toLong()
        ?.div(1024.0) ?: 0.0
