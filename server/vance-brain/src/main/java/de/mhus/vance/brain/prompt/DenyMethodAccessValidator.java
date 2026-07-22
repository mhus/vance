package de.mhus.vance.brain.prompt;

import io.pebbletemplates.pebble.attributes.methodaccess.MethodAccessValidator;
import java.lang.reflect.Method;

/**
 * Denies <b>every</b> method / getter invocation from a Pebble template
 * (code-review F5).
 *
 * <p>Prompt/recipe/setting-form/wizard/template bodies effectively come
 * from DB documents that anyone with document-write can author, so they
 * must be treated as untrusted. Ignoring DoS, the only genuinely
 * dangerous capability a {@code {{ expression }}} has is reaching a
 * method: {@code {{ x.getClass().forName("java.lang.Runtime")… }}}
 * (reflection → RCE) or any sensitive getter / side-effecting method on
 * an object that happens to be in the render context.
 *
 * <p>Pebble's default {@code BlacklistMethodAccessValidator} blocks only
 * {@code getClass}/{@code wait}/{@code notify}/{@code notifyAll} — every
 * other method stays reachable, so the safety of the render depends on
 * the context never holding a rich object. This validator removes that
 * dependency: no method is ever invoked, so the context contents become
 * irrelevant to safety.
 *
 * <p>This does <b>not</b> restrict what templates actually need: Map,
 * List and array attribute access ({@code {{ m.key }}}, {@code {{ xs[0] }}})
 * and registered filters ({@code {{ x | slug }}}) resolve through separate
 * Pebble resolvers that never consult the method-access validator. Only
 * Java-bean getter/method dispatch goes through here — and no render
 * context passes a bean whose method a template relies on.
 */
final class DenyMethodAccessValidator implements MethodAccessValidator {

    @Override
    public boolean isMethodAccessAllowed(Object object, Method method) {
        return false;
    }
}
