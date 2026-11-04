package dev.jaderss.lvr.mvc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bench")
record BenchProperties(
        String ratesUrl,
        Duration downstreamTimeout,
        int poolSize,
        int fanoutThreads,
        boolean legacyLock,
        int legacyLockStripes) {}
