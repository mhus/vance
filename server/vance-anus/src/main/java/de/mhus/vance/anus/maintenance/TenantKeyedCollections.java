package de.mhus.vance.anus.maintenance;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.util.ClassUtils;

/**
 * The collections whose rows are keyed by a tenant: every mapped {@code @Document} on this process's classpath that
 * declares a {@code tenantId} field. Found by scanning, not listed — a collection added next year is part of a
 * tenant's removal the day its class exists, with no handler to forget. The same scan the build-time coverage test
 * of the project machinery uses, run at runtime so addons count too.
 *
 * <p>What it cannot see is a collection written without a document class; {@link TenantMaintenanceService} probes
 * the database for those and refuses to finish while one holds the tenant's rows.
 */
@Slf4j
final class TenantKeyedCollections {

    /** The field that makes a document a tenant's. */
    static final String FIELD = "tenantId";

    private static final String SCAN_ROOT = "de.mhus.vance";

    private TenantKeyedCollections() {}

    /** Collection names, sorted, of every mapped document that carries {@value #FIELD}. */
    static Set<String> scan(MongoTemplate mongo) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
        Set<String> collections = new TreeSet<>();
        for (var candidate : scanner.findCandidateComponents(SCAN_ROOT)) {
            String className = candidate.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> type = ClassUtils.forName(className, TenantKeyedCollections.class.getClassLoader());
                if (declaresTenantId(type)) {
                    collections.add(mongo.getCollectionName(type));
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // A class the process cannot load has no rows here either.
                log.debug("Skipping {} while scanning for tenant-keyed documents: {}", className, e.toString());
            }
        }
        return collections;
    }

    private static boolean declaresTenantId(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (FIELD.equals(field.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
