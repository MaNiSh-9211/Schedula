package com.schedula.api;

import com.schedula.common.model.JobSchedule;
import com.schedula.persistence.ScheduleStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/schedules")
public class SchedulesController {

    public record CreateRequest(UUID tenantId, @NotBlank String name, @NotBlank String jobType,
                                String payload, @Positive long intervalMs,
                                String missedPolicy) {
    }

    private final ScheduleStore schedules;

    public SchedulesController(ScheduleStore schedules) {
        this.schedules = schedules;
    }

    @PostMapping
    org.springframework.http.ResponseEntity<JobSchedule> create(@RequestBody @Valid CreateRequest req) {
        String policy = req.missedPolicy() == null ? "COALESCE" : req.missedPolicy();
        JobSchedule created = schedules.create(new ScheduleStore.Insert(
                req.tenantId() == null ? JobsController.DEFAULT_TENANT : req.tenantId(),
                req.name(), req.jobType(), req.payload() == null ? "{}" : req.payload(),
                req.intervalMs(), policy));
        return org.springframework.http.ResponseEntity
                .created(URI.create("/v1/schedules/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    JobSchedule get(@PathVariable UUID id) {
        return schedules.findById(id).orElseThrow(() -> new NotFoundException("schedule", id));
    }

    @DeleteMapping("/{id}")
    JobSchedule delete(@PathVariable UUID id) {
        schedules.findById(id).orElseThrow(() -> new NotFoundException("schedule", id));
        schedules.setState(id, JobSchedule.State.DELETED);
        return schedules.findById(id).orElseThrow();
    }
}
