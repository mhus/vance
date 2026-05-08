package de.mhus.vance.foot.ide;

import de.mhus.vance.foot.ide.dto.AtMentioned;
import de.mhus.vance.foot.ide.dto.SelectionChanged;

/**
 * Receives IDE notifications. Implementations are registered with
 * {@link IdeNotificationDispatcher#register(IdeBridgeListener)}; the
 * dispatcher fans every incoming notification out to all listeners on
 * the WebSocket reader thread, so handlers must be quick or hand off
 * to their own executor.
 *
 * <p>Defaults are no-ops so listeners only override the events they care
 * about.
 */
public interface IdeBridgeListener {

    default void onAtMentioned(AtMentioned mention) {
    }

    default void onSelectionChanged(SelectionChanged selection) {
    }

    /**
     * Connection-state hook. {@code true} when the handshake completed,
     * {@code false} on any disconnect (plugin shutdown, network error,
     * idle timeout). Listeners that mirror connection state to the UI
     * subscribe here instead of polling {@link IdeBridgeService#isConnected()}.
     */
    default void onConnectionStateChanged(boolean connected) {
    }
}
