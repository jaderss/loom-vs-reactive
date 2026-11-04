package dev.jaderss.lvr.mvc;

import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
class LegacyLock {

    private final Object[] stripes;

    LegacyLock(BenchProperties properties) {
        int count = properties.legacyLock() ? properties.legacyLockStripes() : 0;
        this.stripes = Stream.generate(Object::new).limit(count).toArray();
    }

    <T> T guard(long accountId, Supplier<T> call) {
        if (stripes.length == 0) {
            return call.get();
        }
        synchronized (stripes[(int) Math.floorMod(accountId, (long) stripes.length)]) {
            return call.get();
        }
    }
}
