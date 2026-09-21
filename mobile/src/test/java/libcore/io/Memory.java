package libcore.io;

/**
 * Test-only stub of the ART-internal {@code libcore.io.Memory}.
 *
 * <p>play-services-wearable's DataMap serialiser performs a class-load
 * canary on {@code libcore.io.Memory} in a static initialiser, which
 * throws {@code NoClassDefFoundError} on a plain JVM and makes
 * {@code DataMap.toByteArray}/{@code fromByteArray} unusable in unit
 * tests. Nothing in the serialisation path actually calls Memory
 * methods (verified against play-services-wearable 18.1.0 bytecode),
 * so an empty class is enough to let the wire format round-trip.
 * Test source set only; never ships.
 */
public final class Memory {
    private Memory() {}
}
