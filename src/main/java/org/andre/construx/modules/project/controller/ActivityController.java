package org.andre.construx.modules.project.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ActivityService.ActivityData;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
public class ActivityController {
    private static final String TASKS = "/projects/{projectId}/goals/{goalId}/activities";
    private static final String TASK = TASKS + "/{activityId}";

    private final ActivityService activities;

    public ActivityController(ActivityService activities) {
        this.activities = activities;
    }

    public record ActivityCreate(@NotBlank @Size(max = 160) String name, @NotNull Instant dueAt,
                                 @NotNull UUID assigneeAccessId) {}

    public record ActivityPatch(@Size(max = 160) String name, Instant dueAt) {}

    public record AssigneeInput(@NotNull UUID assigneeAccessId) {}

    @PostMapping(TASKS)
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityData create(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                               @PathVariable UUID goalId, @Valid @RequestBody ActivityCreate body,
                               Authentication authentication) {
        return activities.create(userId(authentication), organizationId, projectId, goalId,
                body.name(), body.dueAt(), body.assigneeAccessId());
    }

    @GetMapping(TASKS)
    public List<ActivityData> list(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                   @PathVariable UUID goalId, @RequestParam(required = false) ActivityStatus status,
                                   @RequestParam(required = false) UUID assigneeAccessId,
                                   Authentication authentication) {
        return activities.list(userId(authentication), organizationId, projectId, goalId, status, assigneeAccessId);
    }

    @GetMapping(TASK)
    public ActivityData get(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                            @PathVariable UUID goalId, @PathVariable UUID activityId, Authentication authentication) {
        return activities.get(userId(authentication), organizationId, projectId, goalId, activityId);
    }

    @PatchMapping(TASK)
    public ActivityData update(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                               @PathVariable UUID goalId, @PathVariable UUID activityId,
                               @Valid @RequestBody ActivityPatch body, Authentication authentication) {
        return activities.update(userId(authentication), organizationId, projectId, goalId, activityId,
                body.name(), body.dueAt());
    }

    @PutMapping(TASK + "/assignee")
    public ActivityData reassign(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                 @PathVariable UUID goalId, @PathVariable UUID activityId,
                                 @Valid @RequestBody AssigneeInput body, Authentication authentication) {
        return activities.reassign(userId(authentication), organizationId, projectId, goalId, activityId,
                body.assigneeAccessId());
    }

    @PostMapping(TASK + "/start")
    public ActivityData start(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                              @PathVariable UUID goalId, @PathVariable UUID activityId, Authentication authentication) {
        return activities.start(userId(authentication), organizationId, projectId, goalId, activityId);
    }

    @PostMapping(TASK + "/complete")
    public ActivityData complete(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                 @PathVariable UUID goalId, @PathVariable UUID activityId,
                                 Authentication authentication) {
        return activities.complete(userId(authentication), organizationId, projectId, goalId, activityId);
    }

    @DeleteMapping(TASK)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID projectId, @PathVariable UUID goalId,
                       @PathVariable UUID activityId, Authentication authentication) {
        activities.delete(userId(authentication), organizationId, projectId, goalId, activityId);
    }

    @GetMapping("/activities/mine")
    public List<ActivityData> mine(@PathVariable UUID organizationId,
                                   @RequestParam(required = false) ActivityStatus status,
                                   Authentication authentication) {
        return activities.mine(userId(authentication), organizationId, status);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
