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

    /** Global write counter; the snapshot store saves to disk when it changes. */
    private static final java.util.concurrent.atomic.AtomicLong MODIFICATIONS =
            new java.util.concurrent.atomic.AtomicLong();

    private final ConcurrentHashMap<ID, T> store = new ConcurrentHashMap<>();
    private final Function<T, ID> idExtractor;

    public static long modificationCount() {
        return MODIFICATIONS.get();
    }

    /** Remove every entity (used when restoring a snapshot). */
    public void clear() {
        store.clear();
        MODIFICATIONS.incrementAndGet();
    }

    protected InMemoryRepository(Function<T, ID> idExtractor) {
        this.idExtractor = idExtractor;
    }

    /** Insert or replace the entity, keyed by its extracted id. */
    public T save(T entity) {
        store.put(idExtractor.apply(entity), entity);
        MODIFICATIONS.incrementAndGet();
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
        MODIFICATIONS.incrementAndGet();
    }
}
