package jtroop.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts an {@code @OnMessage} handler into raw-buffer dispatch. The annotated
 * method must take a {@link java.nio.ByteBuffer} payload parameter (positioned
 * at the start of the message body, after the 2-byte type id) plus any of the
 * standard injectables ({@link jtroop.session.ConnectionId}, {@link Broadcast},
 * {@link Unicast}). No record is decoded for this message type — the handler
 * drives the decode itself.
 *
 * <p>Purpose: bypass the {@code codec.decode(...)} step that would otherwise
 * allocate a {@code Record} plus any non-primitive components it carries (most
 * notably {@code String}, which forces a {@code new String(byte[], UTF_8)}).
 * Handlers that stay on primitive fields or consume text incrementally can run
 * at 0 B/op.
 *
 * <p>The {@link #value()} member names the message type this handler serves,
 * because the {@code ByteBuffer} parameter carries no static type info — the
 * type id is still wire-encoded via the usual {@link jtroop.codec.CodecRegistry}
 * path, and the framework still dispatches by type id.
 *
 * <p>Safety contract: the passed buffer is a reused view over the connection's
 * read buffer. The handler must consume everything it needs before returning.
 * The buffer is invalid once the dispatch call returns.
 *
 * <pre>{@code
 * @OnMessage @ZeroAlloc(ChatMessage.class)
 * void chat(ByteBuffer payload, ConnectionId sender) {
 *     int textLen = payload.getShort() & 0xFFFF;
 *     // ... consume textLen bytes ...
 *     payload.position(payload.position() + textLen);
 *     int room = payload.getInt();
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ZeroAlloc {
    /** The record type whose wire frames this handler consumes. */
    Class<? extends Record> value();
}
