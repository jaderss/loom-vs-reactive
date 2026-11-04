package dev.jaderss.lvr.coroutines

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

private val STATUS = Path.of("/proc/self/status")
private val STAT = Path.of("/proc/self/stat")

@RestController
class StatsController(
    private val properties: BenchProperties,
) {
    @GetMapping("/health")
    fun health() = "ok"

    @GetMapping("/stats")
    fun stats(): Map<String, Any> {
        val linux = Files.isReadable(STAT)
        return mapOf(
            "app" to "coroutines",
            "lang" to "kotlin",
            "version" to "${KotlinVersion.CURRENT}-java${System.getProperty("java.version")}",
            "mode" to "coroutines",
            "cpus" to Runtime.getRuntime().availableProcessors(),
            "pool_size" to properties.poolSize,
            "rss_mb" to if (linux) residentMegabytes(Files.readString(STATUS)) else 0.0,
            "cpu_seconds" to if (linux) cpuSeconds(Files.readString(STAT)) else 0.0,
        )
    }
}

fun residentMegabytes(status: String): Double =
    status
        .lineSequence()
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.split(Regex("\\s+"))
        ?.get(1)
        ?.toLong()
        ?.div(1024.0) ?: 0.0

fun cpuSeconds(stat: String): Double {
    val fields = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
    return (fields[11].toLong() + fields[12].toLong()) / 100.0
}
