package com.spiresurge.net;

import java.util.Collection;
import java.util.Set;

/**
 * What the client currently knows about surging Spire Hearts (packed {@code BlockPos.asLong()} values).
 *
 * Deliberately a plain class with no Minecraft client imports, so the network handler can reference it
 * on a dedicated server without loading any client-only code.
 */
public final class SurgeClientState {

    private static volatile Set<Long> cores = Set.of();

    private SurgeClientState() {
    }

    public static void set(Collection<Long> positions) {
        cores = Set.copyOf(positions);
    }

    public static Set<Long> cores() {
        return cores;
    }

    public static void clear() {
        cores = Set.of();
    }
}
