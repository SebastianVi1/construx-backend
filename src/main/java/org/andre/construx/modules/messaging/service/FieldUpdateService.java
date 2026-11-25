package org.andre.construx.modules.messaging.service;

import org.andre.construx.modules.messaging.conversation.LinkedNumbers;
import org.andre.construx.modules.messaging.model.ConversationSession;
import org.andre.construx.modules.messaging.model.FieldAction;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.OrgScopes;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.OrganizationService;
import org.andre.construx.modules.organization.service.OrganizationService.OrganizationData;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.access.SiteScopes;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.UpdateSource;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ProgressNoteService;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.modules.project.service.ProjectService.ProjectData;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a Field update can reach, with the same reach as the app (contract D2, D8): the Sites of the user's active
 * memberships and the open Tasks they may change, and applying the chosen action through the Task operations with
 * source {@code WHATSAPP}. Lost access or a Task that moved on is {@link Changed}.
 */
@Service
public class FieldUpdateService {

    /** A Site the user can see, with its company. */
    public record SiteChoice(UUID organizationId, String organizationName, UUID siteId, String siteName) {}

    /** An open Task the user may change. */
    public record TaskChoice(UUID phaseId, String phaseName, UUID taskId, String taskName, ActivityStatus status) {}

    /** The open Tasks of a Site the user may change, own Team's first, then soonest due. */
    public record SiteTasks(String siteName, List<TaskChoice> tasks) {}

    /** What the user chose no longer holds: the link, membership, Site, permission or Task changed. */
    public static class Changed extends RuntimeException {
        public Changed() {
            super("The Field update no longer applies");
        }
    }

    private final OrganizationService organizations;
    private final OrgScopes orgScopes;
    private final ProjectService projects;
    private final SiteScopes siteScopes;
    private final ActivityService activities;
    private final ProgressNoteService notes;
    private final LinkedNumbers links;
    private final ConversationSessions sessions;
    private final PendingPhotos photos;

    public FieldUpdateService(OrganizationService organizations, OrgScopes orgScopes, ProjectService projects,
            SiteScopes siteScopes, ActivityService activities, ProgressNoteService notes, LinkedNumbers links,
            ConversationSessions sessions, PendingPhotos photos) {
        this.organizations = organizations;
        this.orgScopes = orgScopes;
        this.projects = projects;
        this.siteScopes = siteScopes;
        this.activities = activities;
        this.notes = notes;
        this.links = links;
        this.sessions = sessions;
        this.photos = photos;
    }

    /** The Sites the user sees in the app, across their active memberships, by company and then by name. */
    @Transactional(readOnly = true)
    public List<SiteChoice> sites(UUID userId) {
        List<SiteChoice> sites = new ArrayList<>();
        for (OrganizationData organization : organizations.listMine(userId)) {
            OrgScope org = orgScopes.org(userId, organization.id());
            if (org.scopeOf(PermissionAction.VIEW_PROJECT).isEmpty()) {
                continue;
            }
            for (ProjectData site : projects.list(org, null)) {
                sites.add(new SiteChoice(organization.id(), organization.name(), site.id(), site.name()));
            }
        }
        return sites;
    }

    /** The open Tasks of the Site the user may start or finish; {@link Changed} without membership or Site. */
    @Transactional(readOnly = true)
    public SiteTasks tasks(UUID userId, UUID organizationId, UUID siteId) {
        SiteScope site = site(userId, organizationId, siteId);
        return new SiteTasks(site.site().getName(), activities.changeableOpenTasks(site).stream()
                .map(task -> new TaskChoice(task.goalId(), task.goalName(), task.id(), task.name(), task.status()))
                .toList());
    }

    /**
     * YES (contract §5): in one transaction, rechecks the link, the account, the membership, the Site, the permission
     * and the Task status, applies the chosen action with source {@code WHATSAPP} and ends the conversation. The
     * pending photos move to a Progress note: the Note's, or a note of photos only after Start or Finish, so they
     * never land on a Pin. When anything no longer holds nothing is saved and {@link Changed} is thrown.
     */
    @Transactional
    public void apply(ConversationSession session) {
        links.find(session.getWaId()).filter(link -> session.belongsTo(link.userId())).orElseThrow(Changed::new);
        SiteScope site = site(session.getUserId(), session.getOrganizationId(), session.getSiteId());
        try {
            switch (session.getAction()) {
                case START -> activities.start(site, session.getPhaseId(), session.getTaskId(), UpdateSource.WHATSAPP);
                case FINISH -> activities.complete(site, session.getPhaseId(), session.getTaskId(),
                        UpdateSource.WHATSAPP);
                case NOTE -> requireOpen(site, session);
            }
            if (session.getAction() == FieldAction.NOTE || photos.count(session.getWaId()) > 0) {
                List<String> photoKeys = photos.claim(session.getWaId(),
                        () -> notes.newPhotoKey(site, session.getTaskId()));
                notes.add(site, session.getPhaseId(), session.getTaskId(), session.getNoteText(), photoKeys,
                        UpdateSource.WHATSAPP);
            }
        } catch (ResourceNotFoundException | AccessDeniedException | IllegalStateException e) {
            // Gone, no longer allowed, or the status moved on (start needs TODO, complete anything but DONE, a note
            // an open Task).
            throw new Changed();
        }
        sessions.end(session.getWaId());
    }

    /** Adding a note checks the permission but not the status, so the Task must still be open and changeable here. */
    private void requireOpen(SiteScope site, ConversationSession session) {
        boolean open = activities.changeableOpenTasks(site).stream().anyMatch(task -> task.id().equals(
                session.getTaskId()) && task.goalId().equals(session.getPhaseId())
                && session.getAction().appliesTo(task.status()));
        if (!open) {
            throw new Changed();
        }
    }

    private SiteScope site(UUID userId, UUID organizationId, UUID siteId) {
        try {
            return siteScopes.site(userId, organizationId, siteId);
        } catch (ResourceNotFoundException e) {
            throw new Changed();
        }
    }
}
