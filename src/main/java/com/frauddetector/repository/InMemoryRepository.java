package com.frauddetector.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Generic thread-safe in-memory store backed by a {@link ConcurrentHashMap}.
 * No external database is available in this sandbox, so repositories keep state
 * in memory for the lifetime of the process.
 *
 * @param <ID> identifier type
 * @param <T>  entity type
 */
public class InMemoryRepository<ID, T> {

    private final ConcurrentHashMap<ID, T> store = new ConcurrentHashMap<>();
    private final Function<T, ID> idExtractor;

    protected InMemoryRepository(Function<T, ID> idExtractor) {
        this.idExtractor = idExtractor;
    }

    /** Insert or replace the entity, keyed by its extracted id. */
    public T save(T entity) {
        store.put(idExtractor.apply(entity), entity);
        return entity;
    }

    public Optional<T> findById(ID id) {
        return Optional.ofNullable(store.get(id));
    }

    public boolean existsById(ID id) {
        return store.containsKey(id);
    }

    public List<T> findAll() {
        return new ArrayList<>(store.values());
    }

    public long count() {
        return store.size();
    }

    public void deleteById(ID id) {
        store.remove(id);
    }
}
