package jtroop;

/**
 * Malformed wire data — framing errors, HTTP protocol violations, codec failures.
 * Thrown when inbound bytes cannot be decoded into a valid message.
 */
public class ProtocolException extends IllegalArgumentException {
    public ProtocolException(String message) { super(message); }
    public ProtocolException(String message, Throwable cause) { super(message, cause); }
}
