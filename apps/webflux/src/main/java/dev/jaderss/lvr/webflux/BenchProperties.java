package dev.jaderss.lvr.webflux;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bench")
record BenchProperties(
        String ratesUrl, Duration downstreamTimeout, Duration dbQueryTimeout, Duration dbSocketTimeout) {}
