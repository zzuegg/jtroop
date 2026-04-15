package bench;

/**
 * Shared message definitions for the game benchmark scenario.
 *
 * Scenario: a game server handling position updates, chat messages,
 * and ability activations from multiple clients. Measures throughput
 * and allocation rate for the full encode→send→receive→decode→dispatch path.
 */
public final class GameMessages {
    private GameMessages() {}

    // Small frequent message — position updates (~20B payload)
    public static final int MSG_POSITION_UPDATE = 1;
    public static final int POSITION_UPDATE_SIZE = 4 * 4; // x, y, z, yaw as floats

    // Medium message — chat (~50-100B payload)
    public static final int MSG_CHAT = 2;

    // Larger message — ability activation with target list (~100-200B)
    public static final int MSG_ABILITY = 3;

    // Response — server acknowledgment
    public static final int MSG_ACK = 4;

    // Benchmark parameters
    public static final int DEFAULT_MESSAGE_COUNT = 10_000;
    public static final int DEFAULT_CLIENT_COUNT = 10;
    public static final String CHAT_TEXT = "Hello from player! This is a typical chat message in a game.";
}
