package app.scapestack.runelite;

import org.junit.Test;

import static org.junit.Assert.*;

public class SyncGateTest {

    @Test
    public void allowsOnlyOneInFlightSyncAtATime() {
        SyncGate gate = new SyncGate();

        assertTrue(gate.tryStart());
        assertTrue(gate.isInFlight());
        assertFalse(gate.tryStart());

        gate.finish();

        assertFalse(gate.isInFlight());
        assertTrue(gate.tryStart());
    }
}
