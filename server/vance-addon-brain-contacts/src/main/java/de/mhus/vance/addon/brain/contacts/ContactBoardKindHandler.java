package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/** Registers {@code contact-board} as a document kind and wires its validation into {@code kind_validate}. */
@Service
public class ContactBoardKindHandler implements KindHandler {

    @Override
    public String getName() {
        return ContactBoardCodec.KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String location = StringUtils.isBlank(ctx.docPath()) ? ContactBoardCodec.KIND : ctx.docPath();
        return ContactBoardCodec.validate(content, location);
    }
}
