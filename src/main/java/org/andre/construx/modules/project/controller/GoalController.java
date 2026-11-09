package org.andre.construx.modules.project.controller;


import org.andre.construx.modules.project.access.SiteScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.project.service.GoalService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/goals")
public class GoalController {
    private final GoalService goals;

    /** A #rrggbb color, or "" to go back to the default. */
    static final String COLOR = "^(#[0-9a-fA-F]{6})?$";

    public GoalController(GoalService goals) {
        this.goals = goals;
    }

    public record GoalCreate(@NotBlank @Size(max = 160) String name, @NotNull Instant startAt, @NotNull Instant dueAt,
                             @Pattern(regexp = COLOR) String color) {}

    public record GoalPatch(@Size(max = 160) String name, Instant startAt, Instant dueAt, @Pattern(regexp = COLOR) String color) {}

    /** Bounded so an absurd offset is a 400, not an overflow. */
    public record GoalShift(@NotNull @Min(-3650) @Max(3650) Integer days) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalService.GoalData create(SiteScope site,
                                       @Valid @RequestBody GoalCreate body) {
        return goals.create(site, body.name(), body.startAt(), body.dueAt(),
                body.color());
    }

    @GetMapping
    public List<GoalService.GoalData> list(SiteScope site) {
        return goals.list(site);
    }

    @GetMapping("/{goalId}")
    public GoalService.GoalData get(SiteScope site,
                                    @PathVariable UUID goalId) {
        return goals.get(site, goalId);
    }

    @PatchMapping("/{goalId}")
    public GoalService.GoalData update(SiteScope site,
                                       @PathVariable UUID goalId, @Valid @RequestBody GoalPatch body) {
        return goals.update(site, goalId, body.name(), body.startAt(),
                body.dueAt(), body.color());
    }

    /** Owner and Finance carve the phase budget out of the site budget; null clears it. */
    @PutMapping("/{goalId}/budget")
    public GoalService.GoalData assignBudget(SiteScope site,
                                             @PathVariable UUID goalId, @Valid @RequestBody BudgetUpdate body) {
        return goals.assignBudget(site, goalId,
                body.requireAllocatedBudget());
    }

    /** Moves the phase and all its tasks by whole days. */
    @PostMapping("/{goalId}/shift")
    public GoalService.GoalData shift(SiteScope site,
                                      @PathVariable UUID goalId, @Valid @RequestBody GoalShift body) {
        return goals.shift(site, goalId, body.days());
    }

    @DeleteMapping("/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(SiteScope site, @PathVariable UUID goalId) {
        goals.delete(site, goalId);
    }
}
