package jtroop.session;

public record ConnectionId(long id) {

    public static final ConnectionId INVALID = new ConnectionId(0L);

    private static final int INDEX_BITS = 32;
    private static final long INDEX_MASK = 0xFFFFFFFFL;

    public static ConnectionId of(int index, int generation) {
        return new ConnectionId(((long) generation << INDEX_BITS) | (index & INDEX_MASK));
    }

    public int index() {
        return (int) (id & INDEX_MASK);
    }

    public int generation() {
        return (int) (id >>> INDEX_BITS);
    }

    public boolean isValid() {
        return generation() > 0;
    }
}
