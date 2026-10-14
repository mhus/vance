package de.mhus.vance.addon.brain.tex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.Lease;
import de.mhus.vance.shared.lease.LeaseBusyException;
import de.mhus.vance.shared.lease.LeaseLostException;
import de.mhus.vance.shared.lease.LeaseRequest;
import de.mhus.vance.shared.lease.LeaseUnavailableException;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import de.mhus.vance.shared.settings.SettingService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lease path of {@link Tex2PdfRbehzadanExecutor}: a busy funnel fails
 * before the service is touched, a revoked lease or an unreachable lease
 * store ends the compile inside the {@code Result} contract, the lease is
 * released on every path, and the compile carries the caller's user into
 * the funnel. The rbehzadan service is a loopback stub that accepts the
 * submit.
 */
class Tex2PdfRbehzadanExecutorLeaseTest {

    @TempDir
    Path workspace;

    private HttpServer server;
    private final AtomicInteger submits = new AtomicInteger();
    private SettingService settings;
    private ResourceLeaseService leaseService;
    private TexCompileTracker tracker;
    private Tex2PdfRbehzadanExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/tex2pdf", ex -> {
            submits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] body = "{\"job_id\":\"job-7\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String url = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();

        settings = mock(SettingService.class);
        when(settings.getStringValueCascade(any(), any(), any(), eq(Tex2PdfRbehzadanExecutor.SETTING_URL)))
                .thenReturn(url);
        when(settings.getStringValueCascade(any(), any(), any(), eq(Tex2PdfRbehzadanExecutor.SETTING_LEASE)))
                .thenReturn("tex");
        leaseService = mock(ResourceLeaseService.class);
        ClusterService cluster = mock(ClusterService.class);
        when(cluster.selfPodId()).thenReturn("pod-1");
        tracker = new TexCompileTracker();
        executor = new Tex2PdfRbehzadanExecutor(JsonMapper.builder().build(), settings, leaseService, cluster, tracker);

        Files.writeString(workspace.resolve("main.tex"), "\\documentclass{article}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private Tex2PdfExecutor.Request request() {
        return new Tex2PdfExecutor.Request("main.tex", "pdflatex", workspace, "acme", "proj", "proc-1", "alice");
    }

    @Test
    void busyLease_failsWithoutTouchingTheService() {
        when(leaseService.acquire(any())).thenThrow(new LeaseBusyException("LEASE_BUSY: 'tex2pdf' level 'project'"));

        Tex2PdfExecutor.Result result = executor.compile(request());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("LEASE_BUSY");
        assertThat(submits.get()).isZero();
    }

    @Test
    void compileLease_carriesTheCallersUser() {
        when(leaseService.acquire(any())).thenThrow(new LeaseBusyException("busy"));

        executor.compile(request());

        ArgumentCaptor<LeaseRequest> captor = ArgumentCaptor.forClass(LeaseRequest.class);
        verify(leaseService).acquire(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo("alice");
        assertThat(captor.getValue().leaseName()).isEqualTo("tex");
    }

    @Test
    void revokedLease_endsThePollLoop_andReleases() {
        Lease lease = mock(Lease.class);
        when(leaseService.acquire(any())).thenReturn(lease);
        doThrow(new LeaseLostException("revoked")).when(lease).check();

        Tex2PdfExecutor.Result result = executor.compile(request());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("lease was revoked");
        assertThat(submits.get()).isEqualTo(1);
        verify(lease).release();
        assertThat(tracker.size()).as("the poll loop deregistered its job").isZero();
    }

    @Test
    void leaseStoreUnavailableDuringPoll_isAFailureResult_notAnException() {
        Lease lease = mock(Lease.class);
        when(leaseService.acquire(any())).thenReturn(lease);
        doThrow(new LeaseUnavailableException("lease store unavailable during check"))
                .when(lease)
                .check();

        Tex2PdfExecutor.Result result = executor.compile(request());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("lease store unavailable");
        verify(lease).release();
    }

    @Test
    void leaseOff_compilesWithoutAcquiring() {
        when(settings.getStringValueCascade(any(), any(), any(), eq(Tex2PdfRbehzadanExecutor.SETTING_LEASE)))
                .thenReturn("none");
        // No poll needed: stop the service so the submit fails fast.
        server.stop(0);

        executor.compile(request());

        verify(leaseService, never()).acquire(any());
    }
}
