package de.mhus.vance.shared.resourceoverride;

import java.util.List;

/**
 * The areas of the bundled resources that can be overridden centrally. A fixed
 * list in code: nothing is written for a name no consumer reads. A new area is
 * a constant here plus its consumer.
 */
public final class ResourceOverrideSections {

    /** Models and providers, {@code _vance/model/}. */
    public static final String MODEL = "model";

    private static final List<String> REGISTERED = List.of(MODEL);

    private ResourceOverrideSections() {}

    public static List<String> registered() {
        return REGISTERED;
    }

    public static boolean isRegistered(String type) {
        return REGISTERED.contains(type);
    }
}
