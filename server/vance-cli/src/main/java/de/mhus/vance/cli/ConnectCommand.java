package de.mhus.vance.cli;

import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.ws.ClientType;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PingData;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.api.ws.client.VanceWebSocketClient;
import de.mhus.vance.api.ws.client.VanceWebSocketClientListener;
import de.mhus.vance.api.ws.client.VanceWebSocketConfig;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;

/**
 * {@code vance connect} — mints a JWT and opens a WebSocket to the Brain.
 *
 * <p>Config (tenant, user, password, URLs) comes from {@code application.yaml}
 * on the classpath. After the handshake the CLI prints every incoming message,
 * starts a ping loop (every 10s), and blocks until the server or the user
 * closes the connection.
 */
@Command(
        name = "connect",
        description = "Log in and hold an open WebSocket connection to the Brain.")
public class ConnectCommand implements Runnable {

    @Override
    public void run() {
        VanceCliConfig cfg = VanceCliConfig.load();
        BrainAccessClient access = new BrainAccessClient();

        AccessTokenResponse token;
        try {
            token = access.mint(
                    cfg.getBrain().getHttpBase(),
                    cfg.getAuth().getTenant(),
                    cfg.getAuth().getUsername(),
                    cfg.getAuth().getPassword());
        } catch (Exception e) {
            System.err.println("Login failed: " + e.getMessage());
            return;
        }
        System.out.println("Minted JWT — expires " + Instant.ofEpochMilli(token.getExpiresAtTimestamp()));

        URI wsUri = URI.create(
                cfg.getBrain().getWsBase() + "/brain/" + cfg.getAuth().getTenant() + "/ws");
        VanceWebSocketConfig wsConfig = VanceWebSocketConfig.builder()
                .uri(wsUri)
                .jwtToken(token.getToken())
                .clientType(ClientType.CLI)
                .clientVersion(cfg.getClient().getVersion())
                .build();

        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicReference<@Nullable VanceWebSocketClient> clientRef = new AtomicReference<>();
        AtomicInteger requestId = new AtomicInteger();
        ScheduledExecutorService pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vance-cli-ping");
            t.setDaemon(true);
            return t;
        });

        VanceWebSocketClient client = new VanceWebSocketClient(wsConfig, new VanceWebSocketClientListener() {
            @Override
            public void onOpen() {
                System.out.println("WebSocket open — " + wsUri);
            }

            @Override
            public void onMessage(WebSocketEnvelope envelope) {
                System.out.println("← " + envelope.getType() + " " + envelope.getData());
            }

            @Override
            public void onClose(int statusCode, @Nullable String reason) {
                System.out.println("WebSocket closed — " + statusCode
                        + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
                pingExecutor.shutdownNow();
                closeLatch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("WebSocket error: " + error.getMessage());
            }
        });
        clientRef.set(client);

        try {
            client.connect().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Connect failed: " + e.getMessage());
            return;
        }

        pingExecutor.scheduleAtFixedRate(() -> {
            VanceWebSocketClient c = clientRef.get();
            if (c == null || !c.isOpen()) {
                return;
            }
            String id = "ping_" + requestId.incrementAndGet();
            WebSocketEnvelope ping = WebSocketEnvelope.request(
                    id,
                    MessageType.PING,
                    PingData.builder().clientTimestamp(System.currentTimeMillis()).build());
            c.send(ping);
        }, 10, 10, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            VanceWebSocketClient c = clientRef.get();
            if (c != null && c.isOpen()) {
                c.close(1000, "bye").join();
            }
        }, "vance-cli-shutdown"));

        try {
            closeLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
