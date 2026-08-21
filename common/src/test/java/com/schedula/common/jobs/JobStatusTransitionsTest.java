package com.schedula.common.jobs;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.schedula.common.jobs.JobStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobStatusTransitionsTest {

    @Test
    void happyPathIsLegal() {
        assertThatCode(() -> {
            assertLegal(CREATED, SCHEDULED);
            assertLegal(SCHEDULED, QUEUED);
            assertLegal(QUEUED, DISPATCHED);
            assertLegal(DISPATCHED, RUNNING);
            assertLegal(RUNNING, COMPLETED);
        }).doesNotThrowAnyException();
    }

    @Test
    void retryLoopIsLegal() {
        assertThatCode(() -> {
            assertLegal(RUNNING, RETRY_WAIT);
            assertLegal(RETRY_WAIT, QUEUED);
            assertLegal(QUEUED, DISPATCHED);
        }).doesNotThrowAnyException();
    }

    @Test
    void completedIsOnlyReachableFromRunning() {
        assertThat(RUNNING.canTransitionTo(COMPLETED)).isTrue();
        for (JobStatus s : values()) {
            if (s == RUNNING) continue;
            assertThat(s.canTransitionTo(COMPLETED)).as(s.name()).isFalse();
        }
        assertThatThrownBy(() -> assertLegal(COMPLETED, RUNNING))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void deadIsReachableOnlyFromRunningOrRetryWait() {
        assertThat(RUNNING.canTransitionTo(DEAD)).isTrue();
        assertThat(RETRY_WAIT.canTransitionTo(DEAD)).isTrue();
        for (JobStatus s : values()) {
            if (s == RUNNING || s == RETRY_WAIT) continue;
            assertThat(s.canTransitionTo(DEAD)).as(s.name()).isFalse();
        }
    }

    @Test
    void cancelReachableOnlyFromPreExecutionStates() {
        for (JobStatus s : Set.of(SCHEDULED, QUEUED, RETRY_WAIT, PAUSED)) {
            assertThat(s.canTransitionTo(CANCELLED)).as(s.name()).isTrue();
        }
        for (JobStatus s : Set.of(DISPATCHED, RUNNING, COMPLETED, FAILED_TERMINAL, DEAD, REJECTED)) {
            assertThat(s.canTransitionTo(CANCELLED)).as(s.name()).isFalse();
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (JobStatus terminal : TERMINAL) {
            for (JobStatus target : values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as(terminal + " -> " + target)
                        .isFalse();
            }
        }
    }

    @Test
    void cancelledQueuedJobCannotBeDispatched() {
        assertThatThrownBy(() -> assertLegal(CANCELLED, DISPATCHED))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void pauseResumeCycle() {
        assertThat(SCHEDULED.canTransitionTo(PAUSED)).isTrue();
        assertThat(QUEUED.canTransitionTo(PAUSED)).isTrue();
        assertThat(PAUSED.canTransitionTo(SCHEDULED)).isTrue();
        assertThat(PAUSED.canTransitionTo(CANCELLED)).isTrue();
        assertThat(RUNNING.canTransitionTo(PAUSED)).isFalse();
    }
}
