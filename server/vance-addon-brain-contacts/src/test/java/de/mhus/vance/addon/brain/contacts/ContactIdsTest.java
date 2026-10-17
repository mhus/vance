package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContactIdsTest {

    private static final String ID = "3f0c8a1e-1111-4222-8333-444455556666";

    @Test
    void normalisesCaseAndUrn() {
        assertThat(ContactIds.normalize("urn:uuid:" + ID.toUpperCase())).isEqualTo(ID);
        assertThat(ContactIds.normalize(" " + ID + " ")).isEqualTo(ID);
        assertThat(ContactIds.normalize("../../etc")).isNull();
    }

    @Test
    void pathsAreBuiltOnlyFromUuids() {
        assertThat(ContactIds.cardPath(ID)).isEqualTo("_vance/contacts/" + ID + ".yaml");
        assertThat(ContactIds.photoPath(ID, "png")).isEqualTo("_vance/contacts/" + ID + ".png");
        assertThatThrownBy(() -> ContactIds.cardPath("../recipes/evil")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContactIds.photoPath(ID, "yaml")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recognisesCardPaths() {
        assertThat(ContactIds.idOfCardPath("_vance/contacts/" + ID + ".yaml")).isEqualTo(ID);
        assertThat(ContactIds.idOfCardPath("_vance/contacts/sub/" + ID + ".yaml"))
                .isNull();
        assertThat(ContactIds.idOfCardPath("_vance/contacts/" + ID + ".jpg")).isNull();
        assertThat(ContactIds.idOfCardPath("notes/" + ID + ".yaml")).isNull();
    }

    @Test
    void tellsUrisFromIds() {
        assertThat(ContactIds.isUri("mailto:a@b")).isTrue();
        assertThat(ContactIds.isUri("urn:uuid:" + ID)).isFalse();
        assertThat(ContactIds.isUri("plain")).isFalse();
    }
}
