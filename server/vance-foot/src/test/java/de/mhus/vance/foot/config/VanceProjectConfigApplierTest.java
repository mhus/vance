package de.mhus.vance.foot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VanceProjectConfigApplierTest {

    private final VanceProjectConfigApplier applier = new VanceProjectConfigApplier();

    @Test
    void appliesEnabledFlag() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getConversationAudit().setEnabled(true);
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getConversationAudit().isEnabled()).isTrue();
    }

    @Test
    void appliesDirWhenPresent() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getConversationAudit().setDir("custom-audit");
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getConversationAudit().getDir()).isEqualTo("custom-audit");
    }

    @Test
    void doesNotOverrideDirWhenAbsent() {
        VanceProjectConfig project = new VanceProjectConfig();
        // dir is null
        FootConfig config = new FootConfig();
        config.getConversationAudit().setDir("pre-existing");

        applier.apply(project, config);

        assertThat(config.getConversationAudit().getDir()).isEqualTo("pre-existing");
    }

    @Test
    void doesNotOverrideDirWhenBlank() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getConversationAudit().setDir("   ");
        FootConfig config = new FootConfig();
        config.getConversationAudit().setDir("pre-existing");

        applier.apply(project, config);

        assertThat(config.getConversationAudit().getDir()).isEqualTo("pre-existing");
    }

    @Test
    void disabledFlagOverridesPreviouslyEnabled() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getConversationAudit().setEnabled(false);
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);

        applier.apply(project, config);

        assertThat(config.getConversationAudit().isEnabled()).isFalse();
    }

    @Test
    void nullConversationAuditSection_isNoOp() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.setConversationAudit(null);
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);

        applier.apply(project, config);

        // Unchanged
        assertThat(config.getConversationAudit().isEnabled()).isTrue();
    }
}
