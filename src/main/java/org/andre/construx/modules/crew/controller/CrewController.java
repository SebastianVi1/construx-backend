package org.andre.construx.modules.crew.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.crew.service.CrewService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/crews")
public class CrewController {

    private final CrewService crewService;

    public CrewController(CrewService crewService) {
        this.crewService = crewService;
    }

    public record CreateCrewRequest(@NotBlank @Size(max = 120) String name, @NotNull UUID leaderMembershipId) {}

    public record AddMemberRequest(@NotNull UUID membershipId) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CrewService.CrewData create(@PathVariable UUID organizationId,
                                       @Valid @RequestBody CreateCrewRequest body,
                                       Authentication authentication) {
        return crewService.createCrew(userId(authentication), organizationId, body.name(), body.leaderMembershipId());
    }

    @GetMapping
    public List<CrewService.CrewData> list(@PathVariable UUID organizationId,
                                           Authentication authentication) {
        return crewService.listCrews(userId(authentication), organizationId);
    }

    @PostMapping("/{crewId}/members")
    public CrewService.CrewData addMember(@PathVariable UUID organizationId,
                                          @PathVariable UUID crewId,
                                          @Valid @RequestBody AddMemberRequest body,
                                          Authentication authentication) {
        return crewService.addMember(userId(authentication), organizationId, crewId, body.membershipId());
    }

    @DeleteMapping("/{crewId}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID organizationId,
                             @PathVariable UUID crewId,
                             @PathVariable UUID membershipId,
                             Authentication authentication) {
        crewService.removeMember(userId(authentication), organizationId, crewId, membershipId);
    }

    @DeleteMapping("/{crewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID organizationId,
                       @PathVariable UUID crewId,
                       Authentication authentication) {
        crewService.deleteCrew(userId(authentication), organizationId, crewId);
    }

    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
