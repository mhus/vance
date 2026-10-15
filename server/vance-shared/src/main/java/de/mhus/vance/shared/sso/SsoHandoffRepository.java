package de.mhus.vance.shared.sso;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link SsoHandoffDocument} — use {@link SsoHandoffService}. */
public interface SsoHandoffRepository extends MongoRepository<SsoHandoffDocument, String> {}
