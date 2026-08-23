package com.schedula.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable workflow definition (versioned). Shape:
 * {"tasks":[{"key":"a","jobType":"log","payload":{},"dependsOn":[],
 *            "maxAttempts":3,"waitMs":null,
 *            "undo":{"jobType":"log","payload":{}}}]}
 */
public record WorkflowDefinition(List<TaskSpec> tasks) {

    public record TaskSpec(String key, String jobType, JsonNode payload,
                           List<String> dependsOn, Integer maxAttempts, Long waitMs,
                           String signalName, String childWorkflow, JsonNode childInput,
                           Undo undo) {
    }

    public record Undo(String jobType, JsonNode payload) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static WorkflowDefinition parse(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            List<TaskSpec> tasks = new ArrayList<>();
            for (JsonNode t : root.path("tasks")) {
                JsonNode undo = t.path("undo");
                JsonNode child = t.path("childWorkflow");
                tasks.add(new TaskSpec(
                        t.path("key").asText(),
                        t.path("jobType").asText(null),
                        t.path("payload"),
                        toStringList(t.path("dependsOn")),
                        t.path("maxAttempts").asInt(3),
                        t.hasNonNull("waitMs") ? t.get("waitMs").asLong() : null,
                        t.hasNonNull("signal") ? t.get("signal").asText() : null,
                        child.isMissingNode() || !child.has("name") ? null
                                : child.path("name").asText(),
                        child.isMissingNode() ? null : child.path("input"),
                        undo.isMissingNode() || !undo.has("jobType") ? null
                                : new Undo(undo.path("jobType").asText(), undo.path("payload"))));
            }
            var def = new WorkflowDefinition(tasks);
            def.validate();
            return def;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid workflow definition: " + e.getMessage(), e);
        }
    }

    public void validate() {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("workflow needs at least one task");
        }
        Map<String, TaskSpec> byKey = new LinkedHashMap<>();
        for (TaskSpec t : tasks) {
            if (t.key() == null || t.key().isBlank()) {
                throw new IllegalArgumentException("every task needs a key");
            }
            if (byKey.putIfAbsent(t.key(), t) != null) {
                throw new IllegalArgumentException("duplicate task key " + t.key());
            }
            boolean isWait = t.waitMs() != null;
            boolean isSignal = t.signalName() != null && !t.signalName().isBlank();
            boolean isChild = t.childWorkflow() != null && !t.childWorkflow().isBlank();
            if (!isWait && !isSignal && !isChild
                    && (t.jobType() == null || t.jobType().isBlank())) {
                throw new IllegalArgumentException("task " + t.key()
                        + " needs jobType, waitMs, signal, or childWorkflow");
            }
        }
        for (TaskSpec t : tasks) {
            for (String dep : deps(t)) {
                if (!byKey.containsKey(dep)) {
                    throw new IllegalArgumentException("task " + t.key()
                            + " depends on unknown task " + dep);
                }
            }
        }
        // cycle check via Kahn's algorithm
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (TaskSpec t : tasks) {
            indegree.putIfAbsent(t.key(), 0);
            for (String dep : deps(t)) {
                adj.computeIfAbsent(dep, k -> new ArrayList<>()).add(t.key());
                indegree.merge(t.key(), 1, Integer::sum);
            }
        }
        Set<String> done = new HashSet<>();
        var queue = new ArrayList<String>(indegree.keySet().stream()
                .filter(k -> indegree.get(k) == 0).toList());
        while (!queue.isEmpty()) {
            String k = queue.remove(queue.size() - 1);
            done.add(k);
            for (String next : adj.getOrDefault(k, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) queue.add(next);
            }
        }
        if (done.size() != tasks.size()) {
            throw new IllegalArgumentException("workflow contains a dependency cycle");
        }
    }

    public static List<String> deps(TaskSpec t) {
        return t.dependsOn() == null ? List.of() : t.dependsOn();
    }

    public String toJson() {
        try {
            var root = JSON.createObjectNode();
            var arr = root.putArray("tasks");
            for (TaskSpec t : tasks) {
                var n = arr.addObject();
                n.put("key", t.key());
                if (t.jobType() != null) n.put("jobType", t.jobType());
                if (t.payload() != null) n.set("payload", t.payload());
                if (t.dependsOn() != null) n.set("dependsOn", JSON.valueToTree(t.dependsOn()));
                if (t.maxAttempts() != null) n.put("maxAttempts", t.maxAttempts());
                if (t.waitMs() != null) n.put("waitMs", t.waitMs());
                if (t.signalName() != null && !t.signalName().isBlank()) n.put("signal", t.signalName());
                if (t.childWorkflow() != null && !t.childWorkflow().isBlank()) {
                    var c = n.putObject("childWorkflow");
                    c.put("name", t.childWorkflow());
                    if (t.childInput() != null) c.set("input", t.childInput());
                }
                if (t.undo() != null) {
                    var u = n.putObject("undo");
                    u.put("jobType", t.undo().jobType());
                    if (t.undo().payload() != null) u.set("payload", t.undo().payload());
                }
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) node.forEach(n -> out.add(n.asText()));
        return out;
    }
}
