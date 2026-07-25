package de.mhus.vance.addon.brain.binder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

class BinderResolverTest {

    @Test
    void stripToPath_stripsVanceSchemeAndQuery() {
        assertThat(BinderResolver.stripToPath("vance:/finance/plan.yaml"))
                .isEqualTo("finance/plan.yaml");
        assertThat(BinderResolver.stripToPath("vance:/finance/plan.yaml?kind=finance-tree"))
                .isEqualTo("finance/plan.yaml");
    }

    @Test
    void stripToPath_stripsCrossProjectAuthority() {
        assertThat(BinderResolver.stripToPath("vance://otherproj/a/b.yaml"))
                .isEqualTo("a/b.yaml");
    }

    @Test
    void stripToPath_acceptsBareAndLeadingSlashPaths() {
        assertThat(BinderResolver.stripToPath("finance/plan.yaml")).isEqualTo("finance/plan.yaml");
        assertThat(BinderResolver.stripToPath("/finance/plan.yaml")).isEqualTo("finance/plan.yaml");
    }

    @Test
    void stripToPath_urlDecodesSegments() {
        assertThat(BinderResolver.stripToPath("vance:/a%20b/c.yaml")).isEqualTo("a b/c.yaml");
    }

    @Test
    void stripToPath_rejectsEmptyRef() {
        assertThatThrownBy(() -> BinderResolver.stripToPath("vance:/"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void canonicalRef_buildsVanceUriWithKind() {
        String ref = BinderResolver.canonicalRef("finance/plan.finance-tree.yaml", "finance-tree");
        assertThat(ref).startsWith("vance:/finance/plan.finance-tree.yaml?kind=finance-tree");
    }

    @Test
    void canonicalRef_defaultsKindWhenNull() {
        String ref = BinderResolver.canonicalRef("notes/a.md", null);
        assertThat(ref).contains("kind=document");
    }

    @Test
    void normaliseFolder_trimsSlashes() {
        assertThat(BinderResolver.normaliseFolder("/finance/plan/")).isEqualTo("finance/plan");
    }

    @Test
    void resolveOutputPath_relativeToFolder() {
        assertThat(BinderResolver.resolveOutputPath("finance/plan", null))
                .isEqualTo("finance/plan/_index.md");
        assertThat(BinderResolver.resolveOutputPath("finance/plan", "/absolute.md"))
                .isEqualTo("absolute.md");
    }
}
