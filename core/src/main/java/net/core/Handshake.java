package net.core;

public final class Handshake {
    private Handshake() {}

    public static final int MAGIC = 0x4E455400; // "NET\0"
    public static final byte ACCEPTED = 1;
    public static final byte REJECTED = 0;
}
