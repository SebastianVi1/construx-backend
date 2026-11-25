package org.andre.construx.modules.project.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.ProgressNote;
import org.andre.construx.modules.project.model.UpdateSource;
import org.andre.construx.modules.project.service.ProgressNoteService;
import org.andre.construx.modules.project.service.ProgressNoteService.ProgressNoteData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Progress notes of a Task. Read and add only: there is deliberately no PUT or DELETE. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/goals/{goalId}/activities/{activityId}/progress-notes")
public class ProgressNoteController {
    private final ProgressNoteService notes;

    public ProgressNoteController(ProgressNoteService notes) {
        this.notes = notes;
    }

    public record ProgressNoteCreate(@Size(max = ProgressNote.MAX_TEXT) String text) {}

    @GetMapping
    public List<ProgressNoteData> list(SiteScope site, @PathVariable UUID goalId, @PathVariable UUID activityId) {
        return notes.list(site, goalId, activityId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgressNoteData add(SiteScope site, @PathVariable UUID goalId, @PathVariable UUID activityId,
                                @Valid @RequestBody ProgressNoteCreate body) {
        return notes.add(site, goalId, activityId, body.text(), UpdateSource.APP);
    }
}
