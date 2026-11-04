package dev.jaderss.lvr.webflux.model;

import java.time.Instant;

public record Txn(long id, Instant bookedAt, long amountMinor, String description) {}
