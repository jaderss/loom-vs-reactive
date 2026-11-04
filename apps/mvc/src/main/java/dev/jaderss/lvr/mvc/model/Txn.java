package dev.jaderss.lvr.mvc.model;

import java.time.Instant;

public record Txn(long id, Instant bookedAt, long amountMinor, String description) {}
