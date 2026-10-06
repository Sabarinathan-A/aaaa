package com.frauddetector.repository;

import com.frauddetector.domain.Provider;

/** In-memory {@link Provider} store keyed by providerId. */
public final class ProviderRepository extends InMemoryRepository<String, Provider> {

    public ProviderRepository() {
        super(Provider::getProviderId);
    }
}
