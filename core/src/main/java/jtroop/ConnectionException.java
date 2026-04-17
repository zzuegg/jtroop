package jtroop;

/**
 * Connection lifecycle errors — session store full, back-pressure timeout,
 * stale connection handle, connection already closed.
 */
public class ConnectionException extends JtroopException {
    public ConnectionException(String message) { super(message); }
    public ConnectionException(String message, Throwable cause) { super(message, cause); }
}
