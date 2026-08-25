package de.mhus.vance.foot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VanceProjectConfigApplierTest {

    private final VanceProjectConfigApplier applier = new VanceProjectConfigApplier();

    @Test
    void appliesEnabledFlag() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getConversationCapture().setEnabled(true);
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getConversationCapture().isEnabled()).isTrue();
    }

    @Test
    void appliesDirWhenPresent() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getConversationCapture().setDir("custom-audit");
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getConversationCapture().getDir()).isEqualTo("custom-audit");
    }

    @Test
    void doesNotOverrideDirWhenAbsent() {
        VanceProjectConfig project = new VanceProjectConfig();
        // dir is null
        FootConfig config = new FootConfig();
        config.getConversationCapture().setDir("pre-existing");

        applier.apply(project, config);

        assertThat(config.getConversationCapture().getDir()).isEqualTo("pre-existing");
    }

    @Test
    void doesNotOverrideDirWhenBlank() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getConversationCapture().setDir("   ");
        FootConfig config = new FootConfig();
        config.getConversationCapture().setDir("pre-existing");

        applier.apply(project, config);

        assertThat(config.getConversationCapture().getDir()).isEqualTo("pre-existing");
    }

    @Test
    void disabledFlagOverridesPreviouslyEnabled() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getConversationCapture().setEnabled(false);
        FootConfig config = new FootConfig();
        config.getConversationCapture().setEnabled(true);

        applier.apply(project, config);

        assertThat(config.getConversationCapture().isEnabled()).isFalse();
    }

    @Test
    void nullConversationCaptureSection_isNoOp() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.setConversationCapture(null);
        FootConfig config = new FootConfig();
        config.getConversationCapture().setEnabled(true);

        applier.apply(project, config);

        // Unchanged
        assertThat(config.getConversationCapture().isEnabled()).isTrue();
    }

    // --- Defaults ---

    @Test
    void defaultsIntellijClaude_enablesIdeClaude() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getDefaults().setIntellijClaude(true);
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getIde().getClaude().isEnabled()).isTrue();
    }

    @Test
    void defaultsIntellijMcpDefault_setsMcpUrl() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getDefaults().setIntellijMcpDefault(true);
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getIde().getIntellijMcp().getUrl())
                .isEqualTo(VanceProjectConfigApplier.DEFAULT_INTELLIJ_MCP_URL);
    }

    @Test
    void defaultsRecipe_setsChatRecipe() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getDefaults().setRecipe("coding");
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getBootstrap().getChatRecipe()).isEqualTo("coding");
    }

    @Test
    void defaultsRecipeBlank_doesNotOverride() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getDefaults().setRecipe("   ");
        FootConfig config = new FootConfig();
        config.getBootstrap().setChatRecipe("pre-existing");

        applier.apply(project, config);

        assertThat(config.getBootstrap().getChatRecipe()).isEqualTo("pre-existing");
    }

    @Test
    void defaultsSandboxFalse_setsNoSandboxDefault() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getDefaults().setSandbox(false);
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getIde().isNoSandboxDefault()).isTrue();
    }

    @Test
    void defaultsRemoteControl_setsRemoteMode() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getDefaults().setRemoteControl("allow");
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getRemote().getMode()).isEqualTo("allow");
    }

    @Test
    void defaultsRemoteControlBlank_doesNotOverride() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.getDefaults().setRemoteControl("   ");
        FootConfig config = new FootConfig();
        config.getRemote().setMode("allow");

        applier.apply(project, config);

        assertThat(config.getRemote().getMode()).isEqualTo("allow");
    }

    @Test
    void defaultsAllDefault_doesNotChangeConfig() {
        VanceProjectConfig project = new VanceProjectConfig();
        // defaults are all false/null by default, except sandbox=true
        FootConfig config = new FootConfig();
        config.getIde().getClaude().setEnabled(false);
        config.getIde().getIntellijMcp().setUrl(null);
        config.getBootstrap().setChatRecipe("pre-existing");
        config.getIde().setNoSandboxDefault(false);
        config.getRemote().setMode("allow");

        applier.apply(project, config);

        assertThat(config.getIde().getClaude().isEnabled()).isFalse();
        assertThat(config.getIde().getIntellijMcp().getUrl()).isNull();
        assertThat(config.getBootstrap().getChatRecipe()).isEqualTo("pre-existing");
        // sandbox defaults to true → noSandboxDefault stays false
        assertThat(config.getIde().isNoSandboxDefault()).isFalse();
        // remoteControl is null → the running mode is left alone
        assertThat(config.getRemote().getMode()).isEqualTo("allow");
    }

    @Test
    void nullDefaultsSection_isNoOp() {
        VanceProjectConfig project = new VanceProjectConfig();
        project.setDefaults(null);
        FootConfig config = new FootConfig();
        config.getIde().getClaude().setEnabled(true);

        applier.apply(project, config);

        // Unchanged
        assertThat(config.getIde().getClaude().isEnabled()).isTrue();
    }

    // ─── toolPacks: selection ───

    @Test
    void absentToolPacksBlock_leavesTheSelectionAlone() {
        VanceProjectConfig project = new VanceProjectConfig();
        FootConfig config = new FootConfig();
        config.getToolPacks().setPacks(new java.util.ArrayList<>(java.util.List.of("chrome")));

        applier.apply(project, config);

        assertThat(config.getToolPacks().isEnabled()).isTrue();
        assertThat(config.getToolPacks().getPacks()).containsExactly("chrome");
    }

    @Test
    void appliesToolPackKillSwitch() {
        VanceProjectConfig project = new VanceProjectConfig();
        VanceProjectConfig.ToolPacks src = new VanceProjectConfig.ToolPacks();
        src.setEnabled(false);
        project.setToolPacks(src);
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getToolPacks().isEnabled()).isFalse();
    }

    @Test
    void appliesAllowAndDenyLists() {
        VanceProjectConfig project = new VanceProjectConfig();
        VanceProjectConfig.ToolPacks src = new VanceProjectConfig.ToolPacks();
        src.setPacks(java.util.List.of("chrome", "projectdb"));
        src.setDisabledPacks(java.util.List.of("jira"));
        project.setToolPacks(src);
        FootConfig config = new FootConfig();

        applier.apply(project, config);

        assertThat(config.getToolPacks().getPacks()).containsExactly("chrome", "projectdb");
        assertThat(config.getToolPacks().getDisabledPacks()).containsExactly("jira");
    }

    @Test
    void killSwitchAlone_doesNotWipeAnExistingAllowList() {
        // "don't steer this field" has to stay distinguishable from
        // "steer it to empty", or setting one field clears the others.
        VanceProjectConfig project = new VanceProjectConfig();
        VanceProjectConfig.ToolPacks src = new VanceProjectConfig.ToolPacks();
        src.setEnabled(false);
        project.setToolPacks(src);
        FootConfig config = new FootConfig();
        config.getToolPacks().setPacks(new java.util.ArrayList<>(java.util.List.of("chrome")));

        applier.apply(project, config);

        assertThat(config.getToolPacks().getPacks()).containsExactly("chrome");
    }
}
