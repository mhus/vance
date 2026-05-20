package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ToolTemplateDescriptorDto;
import de.mhus.vance.api.kit.ToolTemplateInputDto;
import de.mhus.vance.api.kit.ToolTemplatePostInstallDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves a tool-template kit, parses its {@code template.yaml}, and
 * returns the descriptor as a DTO for the {@code describe} REST
 * endpoint. The clone is cleaned up before the call returns — describe
 * is read-only and leaves no artifacts on disk.
 *
 * <p>Used by the Web-UI wizard ("show me the form") and the chat agent
 * ("what inputs do I need to ask the user about?").
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateDescribeService {

    private final KitResolver resolver;
    private final KitWorkspace workspace;

    /**
     * Clones the kit at {@code source}, parses {@code template.yaml},
     * returns the descriptor as a wire DTO.
     *
     * @throws KitException when the source has no {@code template.yaml}
     *                      or the file is invalid
     */
    public ToolTemplateDescriptorDto describe(KitInheritDto source, @Nullable String token) {
        KitResolver.ResolvedKit resolved = null;
        try {
            resolved = resolver.resolve(source, token);
            Path templatePath = resolved.buildRoot().resolve(TemplateApplier.TEMPLATE_FILENAME);
            if (!Files.isRegularFile(templatePath)) {
                throw new KitException("kit at " + source.getUrl()
                        + " (path=" + source.getPath() + ") is not a tool-template — no "
                        + TemplateApplier.TEMPLATE_FILENAME + " in the kit-root");
            }
            String yamlText;
            try {
                yamlText = Files.readString(templatePath);
            } catch (IOException e) {
                throw new KitException("failed to read " + templatePath, e);
            }
            TemplateDescriptor parsed = KitYamlMapper.parseTemplate(yamlText);
            return toDto(parsed);
        } finally {
            if (resolved != null) resolved.cleanup(workspace);
        }
    }

    static ToolTemplateDescriptorDto toDto(TemplateDescriptor d) {
        List<ToolTemplateInputDto> inputs = new ArrayList<>();
        for (TemplateInput in : d.inputs()) {
            List<de.mhus.vance.api.kit.ToolTemplateChoiceDto> choiceDtos = new ArrayList<>();
            for (TemplateChoice c : in.choices()) {
                choiceDtos.add(de.mhus.vance.api.kit.ToolTemplateChoiceDto.builder()
                        .value(c.value())
                        .label(c.label())
                        .defaultSelected(c.defaultSelected())
                        .build());
            }
            inputs.add(ToolTemplateInputDto.builder()
                    .name(in.name())
                    .type(in.type().name().toLowerCase())
                    .label(in.label())
                    .help(in.help())
                    .required(in.required())
                    .defaultValue(in.defaultValue())
                    .choices(choiceDtos)
                    .target(in.target().kind() == TemplateInputTarget.Kind.SETTING
                            ? "setting" : "document-inline")
                    .build());
        }
        ToolTemplatePostInstallDto pi = null;
        if (d.postInstall() != null) {
            pi = ToolTemplatePostInstallDto.builder()
                    .kind(d.postInstall().kind().name().toLowerCase().replace('_', '-'))
                    .provider(d.postInstall().provider())
                    .message(d.postInstall().message())
                    .build();
        }
        return ToolTemplateDescriptorDto.builder()
                .name(d.name())
                .title(d.title())
                .description(d.description())
                .icon(d.icon())
                .inputs(inputs)
                .postInstall(pi)
                .build();
    }
}
