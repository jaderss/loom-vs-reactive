package dev.jaderss.lvr.webflux;

import java.time.Instant;

record Txn(long id, Instant bookedAt, long amountMinor, String description) {}
