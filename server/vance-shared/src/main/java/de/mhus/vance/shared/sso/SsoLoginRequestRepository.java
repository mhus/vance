package de.mhus.vance.shared.sso;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link SsoLoginRequestDocument} — use {@link SsoLoginRequestService}. */
public interface SsoLoginRequestRepository extends MongoRepository<SsoLoginRequestDocument, String> {}
