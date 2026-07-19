package de.mhus.vance.addon.brain.tex;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Selects the LaTeX compilation strategy ({@link Tex2PdfExecutor}) from the
 * {@code tex.executor} setting cascade (Project → {@code _tenant}), falling
 * back to the {@code vance.tex.executor} property.
 *
 * <p>Compilation itself runs through Damogran's {@code tex-task}
 * ({@link TexDamogranTask}) on the already-provisioned compose workspace —
 * this service only resolves which executor to use. (There is no standalone
 * tex2pdf tool/kind anymore; LaTeX is a Damogran compose task.)
 */
@Service
public class TexService {

    static final String SETTING_EXECUTOR = "tex.executor";

    private final SettingService settings;
    private final List<Tex2PdfExecutor> executors;

    @Value("${vance.tex.executor:local}")
    private String defaultExecutorType;

    public TexService(SettingService settings, List<Tex2PdfExecutor> executors) {
        this.settings = settings;
        this.executors = executors != null ? executors : List.of();
    }

    Tex2PdfExecutor resolveExecutor(String tenantId, @Nullable String projectId,
                                    @Nullable String processId) {
        String type = settings.getStringValueCascade(
                tenantId, projectId, processId, SETTING_EXECUTOR);
        if (type == null || type.isBlank()) {
            type = defaultExecutorType != null ? defaultExecutorType : "local";
        }
        type = type.trim();
        for (Tex2PdfExecutor exec : executors) {
            if (exec.type().equalsIgnoreCase(type)) {
                return exec;
            }
        }
        throw new ToolException(
                "No tex executor found for type '" + type
                        + "'. Available: " + executors.stream().map(Tex2PdfExecutor::type).toList()
                        + ". Check the '" + SETTING_EXECUTOR + "' setting or 'vance.tex.executor' property.");
    }
}
