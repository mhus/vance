package de.mhus.vance.foot.ide;

import de.mhus.vance.foot.ide.dto.AtMentioned;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single-slot buffer for the latest {@code at_mentioned} event from the
 * IDE plugin. The plugin fires this when the user explicitly presses
 * "Send to Claude" (Cmd+Esc / Alt+Enter) on a file or selection. The
 * foot stages it here and drains it on the next chat-send so the brain
 * sees it as part of the user's message.
 *
 * <p>Single-shot by design — once {@link #consume()} runs the slot is
 * cleared. If the user changes their mind and never sends, the staged
 * mention sits there until the next mention overwrites it. No TTL,
 * no UI badge in v1 — kept deliberately minimal per planning §3.6.
 */
@Component
public class IdeAtMentionPending implements IdeBridgeListener {

    private final IdeNotificationDispatcher dispatcher;
    private final AtomicReference<@Nullable AtMentioned> slot = new AtomicReference<>();

    public IdeAtMentionPending(IdeNotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostConstruct
    void subscribe() {
        dispatcher.register(this);
    }

    @Override
    public void onAtMentioned(AtMentioned mention) {
        slot.set(mention);
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        if (!connected) {
            slot.set(null);
        }
    }

    /** Atomically takes and clears the pending mention. */
    public Optional<AtMentioned> consume() {
        return Optional.ofNullable(slot.getAndSet(null));
    }

    /** Non-destructive peek for diagnostics — does not clear the slot. */
    public Optional<AtMentioned> peek() {
        return Optional.ofNullable(slot.get());
    }
}
