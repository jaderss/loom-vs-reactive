package dev.jaderss.lvr.mvc.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record Rates(Map<String, BigDecimal> rates) {

    public Rates {
        Objects.requireNonNull(rates, "rates");
    }

    public Optional<BigDecimal> usdPer(String currency) {
        return Optional.ofNullable(rates.get(currency));
    }
}
