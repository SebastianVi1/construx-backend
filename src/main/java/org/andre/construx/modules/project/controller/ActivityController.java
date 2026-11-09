package org.andre.construx.modules.project.controller;


import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.organization.access.OrgScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.TaskRole;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ActivityService.ActivityData;
import org.andre.construx.shared.validation.BudgetUpdate;
import org.springframework.http.HttpStatus;
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

    public record ActivityCreate(@NotBlank @Size(max = 160) String name, @NotNull Instant startAt, @NotNull Instant dueAt,
                                 @NotEmpty @Size(max = 100) List<@Valid @NotNull TeamPlace> assignees,
                                 @Pattern(regexp = GoalController.COLOR) String color,
                                 @Size(max = 500) String description) {}

    public record ActivityPatch(@Size(max = 160) String name, Instant startAt, Instant dueAt,
                                @Pattern(regexp = GoalController.COLOR) String color,
                                @Size(max = 500) String description) {}

    public record TeamPlace(@NotNull UUID accessId, @NotNull TaskRole taskRole) {}

    public record TeamInput(@NotEmpty @Size(max = 100) List<@Valid @NotNull TeamPlace> assignees) {}

    @PostMapping(TASKS)
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityData create(SiteScope site,
                               @PathVariable UUID goalId, @Valid @RequestBody ActivityCreate body) {
        return activities.create(site, goalId,
                body.name(), body.startAt(), body.dueAt(), team(body.assignees()), body.color(), body.description());
    }

    @GetMapping(TASKS)
    public List<ActivityData> list(SiteScope site,
                                   @PathVariable UUID goalId, @RequestParam(required = false) ActivityStatus status,
                                   @RequestParam(required = false) UUID assigneeAccessId) {
        return activities.list(site, goalId, status, assigneeAccessId);
    }

    @GetMapping(TASK)
    public ActivityData get(SiteScope site,
                            @PathVariable UUID goalId, @PathVariable UUID activityId) {
        return activities.get(site, goalId, activityId);
    }

    @PatchMapping(TASK)
    public ActivityData update(SiteScope site,
                               @PathVariable UUID goalId, @PathVariable UUID activityId,
                               @Valid @RequestBody ActivityPatch body) {
        return activities.update(site, goalId, activityId,
                body.name(), body.startAt(), body.dueAt(), body.color(), body.description());
    }

    /** Owner and Finance carve the task budget out of its phase budget; null clears it. */
    @PutMapping(TASK + "/budget")
    public ActivityData assignBudget(SiteScope site,
                                     @PathVariable UUID goalId, @PathVariable UUID activityId,
                                     @Valid @RequestBody BudgetUpdate body) {
        return activities.assignBudget(site, goalId, activityId,
                body.requireAllocatedBudget());
    }

    /** Replaces the whole team; exactly one member is the LEAD. */
    @PutMapping(TASK + "/assignees")
    public ActivityData assignTeam(SiteScope site,
                                   @PathVariable UUID goalId, @PathVariable UUID activityId,
                                   @Valid @RequestBody TeamInput body) {
        return activities.assignTeam(site, goalId, activityId,
                team(body.assignees()));
    }

    @PostMapping(TASK + "/start")
    public ActivityData start(SiteScope site,
                              @PathVariable UUID goalId, @PathVariable UUID activityId) {
        return activities.start(site, goalId, activityId);
    }

    @PostMapping(TASK + "/complete")
    public ActivityData complete(SiteScope site,
                                 @PathVariable UUID goalId, @PathVariable UUID activityId) {
        return activities.complete(site, goalId, activityId);
    }

    @DeleteMapping(TASK)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(SiteScope site, @PathVariable UUID goalId,
                       @PathVariable UUID activityId) {
        activities.delete(site, goalId, activityId);
    }

    @GetMapping("/activities/mine")
    public List<ActivityData> mine(OrgScope org,
                                   @RequestParam(required = false) ActivityStatus status) {
        return activities.mine(org, status);
    }

    private static List<ActivityService.TeamInput> team(List<TeamPlace> places) {
        return places.stream().map(p -> new ActivityService.TeamInput(p.accessId(), p.taskRole())).toList();
    }
}
