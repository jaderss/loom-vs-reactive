package dev.jaderss.lvr.webflux;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record Rates(Map<String, BigDecimal> rates) {

    Rates {
        Objects.requireNonNull(rates, "rates");
    }

    Optional<BigDecimal> usdPer(String currency) {
        return Optional.ofNullable(rates.get(currency));
    }
}
