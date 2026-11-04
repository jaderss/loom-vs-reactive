package dev.jaderss.lvr.mvc;

import java.time.Instant;

record Txn(long id, Instant bookedAt, long amountMinor, String description) {}
