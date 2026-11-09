package org.andre.construx.modules.crew.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.crew.service.CrewService;
import org.springframework.http.HttpStatus;
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
    public CrewService.CrewData create(OrgScope org,
                                       @Valid @RequestBody CreateCrewRequest body) {
        return crewService.createCrew(org, body.name(), body.leaderMembershipId());
    }

    @GetMapping
    public List<CrewService.CrewData> list(OrgScope org) {
        return crewService.listCrews(org);
    }

    @PostMapping("/{crewId}/members")
    public CrewService.CrewData addMember(OrgScope org,
                                          @PathVariable UUID crewId,
                                          @Valid @RequestBody AddMemberRequest body) {
        return crewService.addMember(org, crewId, body.membershipId());
    }

    @DeleteMapping("/{crewId}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(OrgScope org,
                             @PathVariable UUID crewId,
                             @PathVariable UUID membershipId) {
        crewService.removeMember(org, crewId, membershipId);
    }

    @DeleteMapping("/{crewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(OrgScope org,
                       @PathVariable UUID crewId) {
        crewService.deleteCrew(org, crewId);
    }

}
