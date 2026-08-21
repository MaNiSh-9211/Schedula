package com.schedula.worker;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class HandlerRegistry {

    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();

    public void register(String jobType, JobHandler handler) {
        JobHandler prev = handlers.putIfAbsent(jobType, handler);
        if (prev != null) {
            throw new IllegalStateException("handler already registered for type " + jobType);
        }
    }

    public Optional<JobHandler> find(String jobType) {
        return Optional.ofNullable(handlers.get(jobType));
    }
}
