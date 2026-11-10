package io.github.gazehighlighter;

/** An epoch anchor for exports with monotonic elapsed time despite wall-clock adjustments. */
final class GazeClock {
    private static final long EPOCH=System.currentTimeMillis(), MONO=System.nanoTime();
    static long now() { return EPOCH+(System.nanoTime()-MONO)/1_000_000; }
}
