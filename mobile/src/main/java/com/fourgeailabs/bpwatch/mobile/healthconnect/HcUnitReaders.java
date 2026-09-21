package com.fourgeailabs.bpwatch.mobile.healthconnect;

import androidx.health.connect.client.units.Energy;
import androidx.health.connect.client.units.Length;
import androidx.health.connect.client.units.Mass;
import androidx.health.connect.client.units.Volume;

/**
 * Reads values out of Health Connect's units classes (Mass, Length, Energy,
 * Volume).
 *
 * Why this exists: the Kotlin compiler (K2) cannot see the instance getters
 * on these classes — e.g. both {@code mass.kilograms} and
 * {@code mass.getKilograms()} fail with "unresolved reference", even though
 * the methods are public in the bytecode. (The companion factories like
 * {@code Mass.kilograms()} resolve fine; only instance members are
 * affected.) javac reads the same bytecode without trouble, so this tiny
 * Java bridge exposes the getters to Kotlin.
 */
public final class HcUnitReaders {
    private HcUnitReaders() {
    }

    public static double kilograms(Mass mass) {
        return mass.getKilograms();
    }

    public static double meters(Length length) {
        return length.getMeters();
    }

    public static double kilocalories(Energy energy) {
        return energy.getKilocalories();
    }

    public static double liters(Volume volume) {
        return volume.getLiters();
    }
}
