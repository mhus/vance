package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Registers {@code contact} as a document kind and wires its validation into
 * {@code kind_validate}. The address-book folder is claimed by path: a YAML
 * file under {@code _vance/contacts/} is a contact card whatever its body
 * says. No body detection: a card's keys ({@code fullName}, {@code emails})
 * are too common to claim a document by.
 */
@Service
public class ContactKindHandler implements KindHandler {

    @Override
    public String getName() {
        return ContactCodec.KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String location = StringUtils.isBlank(ctx.docPath()) ? ContactCodec.KIND : ctx.docPath();
        return ContactCodec.validate(content, location);
    }

    @Override
    public boolean detectsPath(String documentPath) {
        return ContactIds.idOfCardPath(documentPath) != null;
    }
}
