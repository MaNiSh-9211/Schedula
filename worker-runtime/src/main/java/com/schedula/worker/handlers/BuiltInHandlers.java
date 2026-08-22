package com.schedula.worker.handlers;

import com.schedula.common.retry.ErrorClass;
import com.schedula.worker.ClassifiedException;
import com.schedula.worker.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Built-in handlers. Job types map to these via HandlerRegistry; unknown types fail as
 * PERMANENT at the worker before any attempt is made.
 */
public final class BuiltInHandlers {

    private BuiltInHandlers() {
    }

    public static class LogHandler implements com.schedula.worker.JobHandler {
        private static final Logger log = LoggerFactory.getLogger("schedula.job.log");

        @Override
        public String handle(JobContext ctx) {
            log.info("job executed tenant={} job={} type={} payload={}",
                    ctx.tenantId(), ctx.jobId(), ctx.jobType(), ctx.payloadJson());
            return null;
        }
    }

    public static class SleepHandler implements com.schedula.worker.JobHandler {
        @Override
        public String handle(JobContext ctx) throws Exception {
            long remaining = extractMs(ctx.payloadJson());
            // cooperative: wake every 50ms to observe the cancellation token
            while (remaining > 0 && !ctx.cancellation().isCancelled()) {
                long slice = Math.min(50, remaining);
                Thread.sleep(slice);
                remaining -= slice;
            }
            return null;
        }

        private static long extractMs(String json) {
            var m = java.util.regex.Pattern.compile("\"ms\"\\s*:\\s*([0-9]+)")
                    .matcher(json == null ? "" : json);
            return m.find() ? Long.parseLong(m.group(1)) : 100;
        }
    }

    public static class HttpCallbackHandler implements com.schedula.worker.JobHandler {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        @Override
        public String handle(JobContext ctx) throws Exception {
            String url = extractStringField(ctx.payloadJson(), "url");
            if (url == null || url.isBlank()) {
                throw new ClassifiedException(ErrorClass.VALIDATION, "http job requires payload.url");
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Schedula-Job-Id", ctx.jobId().toString())
                    .header("X-Schedula-Execution-Id", ctx.executionId().toString())
                    .header("X-Idempotency-Key", idempotencyKey(ctx))
                    .POST(HttpRequest.BodyPublishers.ofString(ctx.payloadJson()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 500) {
                throw new ClassifiedException(ErrorClass.TRANSIENT, "callback returned " + code);
            }
            if (code == 429) {
                throw new ClassifiedException(ErrorClass.THROTTLED, "callback throttled");
            }
            if (code >= 400) {
                throw new ClassifiedException(ErrorClass.PERMANENT, "callback rejected " + code);
            }
            return "{\"callbackStatus\":" + code + "}";
        }

        private static String idempotencyKey(JobContext ctx) {
            return ctx.tenantId() + ":" + ctx.jobId() + ":" + ctx.attempt();
        }

        private static String extractStringField(String json, String field) {
            var matcher = java.util.regex.Pattern
                    .compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(json == null ? "" : json);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}

