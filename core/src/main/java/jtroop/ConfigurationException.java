package jtroop;

/**
 * Builder and setup validation errors — missing annotations, unsupported types,
 * invalid parameters, registration failures.
 */
public class ConfigurationException extends JtroopException {
    public ConfigurationException(String message) { super(message); }
    public ConfigurationException(String message, Throwable cause) { super(message, cause); }
}
