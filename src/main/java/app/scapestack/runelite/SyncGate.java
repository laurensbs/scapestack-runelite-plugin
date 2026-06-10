package app.scapestack.runelite;

import java.util.concurrent.atomic.AtomicBoolean;

final class SyncGate {
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    boolean tryStart() {
        return inFlight.compareAndSet(false, true);
    }

    void finish() {
        inFlight.set(false);
    }

    boolean isInFlight() {
        return inFlight.get();
    }
}
