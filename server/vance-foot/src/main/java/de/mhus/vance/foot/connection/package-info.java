/**
 * WebSocket connection to the Brain plus the in-process handler framework
 * that routes incoming messages.
 *
 * <p>{@link de.mhus.vance.foot.connection.ConnectionService} wraps the
 * {@code VanceWebSocketClient} (from {@code vance-api}) with lifecycle and
 * thread-safe send. {@link de.mhus.vance.foot.connection.MessageDispatcher}
 * dispatches incoming envelopes to {@link de.mhus.vance.foot.connection.MessageHandler}
 * beans by message type — so adding new message types means dropping in a
 * new {@code @Component} handler.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.foot.connection;
