package com.dreamdisplays.util;

import org.jspecify.annotations.NullMarked;

/**
 * Enum representing the four cardinal facings.
 */
@NullMarked
public enum FacingUtil {
    NORTH,
    EAST,
    SOUTH,
    WEST;

    public static FacingUtil fromPacket(byte data) {
        if (
                data < 0 || data >= values().length
        ) throw new IllegalArgumentException("Invalid facing ID: " + data);
        return values()[data];
    }

    public byte toPacket() {
        return (byte) this.ordinal();
    }
}
