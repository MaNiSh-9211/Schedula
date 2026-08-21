package com.schedula.worker;

import com.schedula.worker.handlers.BuiltInHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit registration of built-in handlers. Deliberately not component-scanned:
 * the handler set is part of the worker's contract, not classpath magic.
 */
@Configuration
public class HandlerConfig {

    @Bean
    public HandlerRegistry handlerRegistry() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("log", new BuiltInHandlers.LogHandler());
        registry.register("sleep", new BuiltInHandlers.SleepHandler());
        registry.register("http", new BuiltInHandlers.HttpCallbackHandler());
        return registry;
    }
}
