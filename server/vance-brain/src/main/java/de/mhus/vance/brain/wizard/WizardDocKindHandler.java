package de.mhus.vance.brain.wizard;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-wizard} kind — one wizard
 * definition under {@code _vance/wizards/<name>.yaml}, four-tier cascade
 * {@code project → _user → _vance → classpath} (spec
 * {@code specification/wizards.md}). Path-based marker: the wizard tree
 * is the identity, the loader never looks at kind tags.
 *
 * <p><b>Validation delegates to the canonical parser.</b>
 * {@link WizardLoader#listAll} skips a wizard whose YAML does not parse —
 * WARN log, the wizard silently disappears from every surface — the
 * recipe-handler situation again. The handler runs the same parse via the
 * {@link WizardLoader#validateBody} seam: a finding means exactly what the
 * loader does, because it is the same code that says it. That covers the
 * whole grammar: localized {@code title}/{@code description}, at least one
 * field, a Pebble-compiling {@code promptTemplate} (an optional
 * {@code validatorPrompt} compiles too), and the {@code availableIn} glob
 * shape.
 */
@Service
public class WizardDocKindHandler implements KindHandler {

    public static final String KIND = "vance-wizard";

    private final WizardLoader wizardLoader;

    public WizardDocKindHandler(WizardLoader wizardLoader) {
        this.wizardLoader = wizardLoader;
    }

    @Override
    public String getName() {
        return KIND;
    }

    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(WizardLoader.WIZARD_PATH_PREFIX);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        String name = ctx.docPath() == null ? KIND : StringUtils.substringAfterLast(ctx.docPath(), "/");
        List<Finding> findings = new ArrayList<>();
        try {
            wizardLoader.validateBody(name, content);
        } catch (RuntimeException e) {
            findings.add(Finding.error(target, "wizard-parse", "the loader skips this wizard: " + e.getMessage()));
        }
        return findings;
    }
}
