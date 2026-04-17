package jtroop;

/**
 * Base exception for all jtroop framework errors. Extends {@link RuntimeException}
 * so callers are not forced to catch — but can catch this single type to handle
 * all jtroop-specific failures.
 */
public class JtroopException extends RuntimeException {
    public JtroopException(String message) { super(message); }
    public JtroopException(String message, Throwable cause) { super(message, cause); }
}
