package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.PhotoPhase;
import org.andre.construx.modules.floorplan.model.PinKind;
import org.andre.construx.modules.floorplan.model.PinStatus;
import org.andre.construx.modules.floorplan.model.PlanPin;
import org.andre.construx.modules.floorplan.model.PlanPinPhoto;
import org.andre.construx.modules.floorplan.repository.FloorPlanCalibrationRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.modules.floorplan.repository.PlanPinPhotoRepository;
import org.andre.construx.modules.floorplan.repository.PlanPinRepository;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.service.ProjectAccessService;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.audit.AuditService.AuditEventData;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Issues and progress photos pinned on a floor plan. Anyone who can view the plan reports; the reporter keeps
 * their pin while it is a photo or an open issue; everything else takes MANAGE_PLAN.
 */
@Service
public class PlanPinService {
    static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;
    static final int MAX_PHOTOS_PER_PIN = 10;
    static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration VIEW_TTL = Duration.ofHours(1);
    private static final String AUDIT_ENTITY = "PlanPin";

    private final PlanPinRepository pins;
    private final PlanPinPhotoRepository photos;
    private final FloorPlanZoneRepository zones;
    private final FloorPlanCalibrationRepository calibrations;
    private final FloorPlanLookup lookup;
    private final ObjectStorage storage;
    private final AuditService auditService;
    private final ProjectAccessService siteStaff;
    private final MembershipDirectory memberships;
    private final PersonDirectory directory;

    public PlanPinService(PlanPinRepository pins, PlanPinPhotoRepository photos, FloorPlanZoneRepository zones,
                          FloorPlanCalibrationRepository calibrations, FloorPlanLookup lookup, ObjectStorage storage, AuditService auditService,
                          ProjectAccessService siteStaff, MembershipDirectory memberships,
                          PersonDirectory directory) {
        this.pins = pins;
        this.photos = photos;
        this.zones = zones;
        this.calibrations = calibrations;
        this.lookup = lookup;
        this.storage = storage;
        this.auditService = auditService;
        this.siteStaff = siteStaff;
        this.memberships = memberships;
        this.directory = directory;
    }

    /** {@code key} is sent back as a photo key once the bytes are uploaded. */
    public record PhotoUploadData(String key, URI uploadUrl, Map<String, String> headers, Instant expiresAt) {}

    public record PersonRef(UUID membershipId, String fullName) {}

    /** What the caller may do with the pin, by the same rules the service enforces. */
    public record PinActions(boolean edit, boolean assign, boolean resolve, boolean close, boolean reopen,
                             boolean delete) {}

    /** {@code url} is a presigned download URL that expires after an hour. */
    public record PhotoData(UUID id, PhotoPhase phase, URI url, Instant createdAt) {}

    /** {@code photos} is null in lists; {@code zoneId} is the zone the pin sits in today, if any. */
    public record PinData(UUID id, UUID floorPlanId, PinKind kind, double x, double y, String title,
                          String description, PinStatus status, PersonRef assignee, PersonRef createdBy,
                          UUID zoneId, String zoneName, int photoCount, URI coverUrl, long version,
                          Instant createdAt, Instant resolvedAt, Instant closedAt, PinActions can,
                          List<PhotoData> photos) {}

    public record CreatePinRequest(PinKind kind, double x, double y, String title, String description,
                                   UUID assigneeMembershipId, List<String> photoKeys) {}

    public record UpdatePinRequest(String title, String description, double x, double y,
                                   UUID assigneeMembershipId, long version) {}

    public record PinFilter(PinKind kind, PinStatus status, boolean assignedToMe, UUID zoneId) {}

    /** Presigns an upload for a photo of this plan; it only counts once a pin names its key. */
    @Transactional(readOnly = true)
    public PhotoUploadData requestPhotoUpload(SiteScope site, UUID floorPlanId, String contentType, long sizeBytes) {
        site.require(PermissionAction.VIEW_PLAN);
        FloorPlan plan = readyPlan(site, floorPlanId);
        String type = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        if (!PHOTO_TYPES.contains(type)) {
            throw new RuleViolationException("PIN_PHOTO_INVALID", "Pin photos must be JPEG, PNG or WebP");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_PHOTO_BYTES) {
            throw new RuleViolationException("PIN_PHOTO_INVALID", "Pin photos must be at most 5 MB");
        }
        String key = photoPrefix(site, plan) + UUID.randomUUID();
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, type, sizeBytes, UPLOAD_TTL);
        return new PhotoUploadData(key, upload.url(), upload.headers(), upload.expiresAt());
    }

    @Transactional
    public PinData create(SiteScope site, UUID floorPlanId, CreatePinRequest request) {
        site.require(PermissionAction.VIEW_PLAN);
        OrganizationMembership actor = site.membership();
        FloorPlan plan = readyPlan(site, floorPlanId);
        List<String> keys = request.photoKeys() == null ? List.of() : request.photoKeys();

        PlanPin pin;
        if (request.kind() == PinKind.PHOTO) {
            if (keys.isEmpty()) {
                throw new RuleViolationException("PIN_PHOTO_REQUIRED", "A progress photo pin needs a photo");
            }
            pin = PlanPin.photo(plan, actor, request.x(), request.y(), request.title(), request.description());
        } else {
            pin = PlanPin.issue(plan, actor, request.x(), request.y(), request.title(), request.description());
        }
        if (request.assigneeMembershipId() != null) {
            site.require(PermissionAction.MANAGE_PLAN);
            pin.assign(siteStaffMember(site, request.assigneeMembershipId()));
        }
        pins.save(pin);
        attach(site, pin, actor, keys, PhotoPhase.REPORT);
        auditService.record(AUDIT_ENTITY, pin.getId(), "CREATE", actor.getId(), null, snapshot(pin));
        return detail(site, pin);
    }

    @Transactional(readOnly = true)
    public List<PinData> list(SiteScope site, UUID floorPlanId, PinFilter filter) {
        site.require(PermissionAction.VIEW_PLAN);
        FloorPlan plan = lookup.floorPlan(site, floorPlanId);
        List<PlanPin> found = pins.findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtDesc(plan.getId()).stream()
                .filter(p -> filter.kind() == null || p.getKind() == filter.kind())
                .filter(p -> filter.status() == null || p.getStatus() == filter.status())
                .filter(p -> !filter.assignedToMe() || isAssignee(site, p))
                .toList();
        Map<UUID, List<PlanPinPhoto>> photosByPin = found.isEmpty() ? Map.of()
                : photos.findByPinIdInOrderByCreatedAtAsc(found.stream().map(PlanPin::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ph -> ph.getPin().getId()));
        View view = view(site, plan, found);
        return found.stream()
                .map(p -> view.data(p, photosByPin.getOrDefault(p.getId(), List.of()), false))
                .filter(d -> filter.zoneId() == null || filter.zoneId().equals(d.zoneId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PinData get(SiteScope site, UUID floorPlanId, UUID pinId) {
        site.require(PermissionAction.VIEW_PLAN);
        return detail(site, pin(site, floorPlanId, pinId));
    }

    @Transactional
    public PinData update(SiteScope site, UUID floorPlanId, UUID pinId, UpdatePinRequest request) {
        site.require(PermissionAction.VIEW_PLAN);
        PlanPin pin = pin(site, floorPlanId, pinId);
        requireEdit(site, pin);
        if (request.version() != pin.getVersion()) {
            throw conflict();
        }
        Map<String, Object> before = snapshot(pin);
        pin.edit(request.title(), request.description(), request.x(), request.y());
        if (!Objects.equals(request.assigneeMembershipId(), assigneeId(pin))) {
            site.require(PermissionAction.MANAGE_PLAN);
            pin.assign(request.assigneeMembershipId() == null
                    ? null : siteStaffMember(site, request.assigneeMembershipId()));
        }
        flush(pin);
        auditService.record(AUDIT_ENTITY, pin.getId(), "UPDATE", site.membershipId(), before, snapshot(pin));
        return detail(site, pin);
    }

    /** Soft delete: the pin disappears and its photos stay in storage. */
    @Transactional
    public void delete(SiteScope site, UUID floorPlanId, UUID pinId) {
        site.require(PermissionAction.VIEW_PLAN);
        PlanPin pin = pin(site, floorPlanId, pinId);
        requireEdit(site, pin);
        Map<String, Object> before = snapshot(pin);
        pin.softDelete();
        pins.save(pin);
        auditService.record(AUDIT_ENTITY, pin.getId(), "DELETE", site.membershipId(), before, null);
    }

    /**
     * Moves an issue along OPEN → RESOLVED → CLOSED. The assignee or a manager resolves; only a manager closes
     * (also straight from OPEN) or reopens. Photos sent with a resolve or a close are RESOLUTION photos.
     */
    @Transactional
    public PinData changeStatus(SiteScope site, UUID floorPlanId, UUID pinId, PinStatus status,
                                List<String> photoKeys) {
        site.require(PermissionAction.VIEW_PLAN);
        PlanPin pin = pin(site, floorPlanId, pinId);
        if (status != PinStatus.RESOLVED || !isAssignee(site, pin)) {
            site.require(PermissionAction.MANAGE_PLAN);
        }
        Map<String, Object> before = snapshot(pin);
        switch (status) {
            case RESOLVED -> pin.resolve();
            case CLOSED -> pin.close();
            case OPEN -> pin.reopen();
        }
        if (status != PinStatus.OPEN && photoKeys != null) {
            attach(site, pin, site.membership(), photoKeys, PhotoPhase.RESOLUTION);
        }
        flush(pin);
        auditService.record(AUDIT_ENTITY, pin.getId(), "STATUS", site.membershipId(), before, snapshot(pin));
        return detail(site, pin);
    }

    /** REPORT photos follow the right to edit the pin; RESOLUTION photos belong to the assignee and managers. */
    @Transactional
    public PinData addPhotos(SiteScope site, UUID floorPlanId, UUID pinId, List<String> keys, PhotoPhase phase) {
        site.require(PermissionAction.VIEW_PLAN);
        PlanPin pin = pin(site, floorPlanId, pinId);
        if (phase == PhotoPhase.RESOLUTION) {
            if (!isAssignee(site, pin)) {
                site.require(PermissionAction.MANAGE_PLAN);
            }
            if (!pin.isIssue()) {
                throw new RuleViolationException("PIN_NOT_AN_ISSUE", "Only issues have resolution photos");
            }
        } else {
            requireEdit(site, pin);
        }
        List<PlanPinPhoto> attached = attach(site, pin, site.membership(), keys == null ? List.of() : keys, phase);
        auditService.record(AUDIT_ENTITY, pin.getId(), "PHOTO_ADD", site.membershipId(), null,
                Map.of("phase", phase, "count", attached.size()));
        return detail(site, pin);
    }

    /** Removes the photo and its file. A progress photo pin always keeps one photo. */
    @Transactional
    public void deletePhoto(SiteScope site, UUID floorPlanId, UUID pinId, UUID photoId) {
        site.require(PermissionAction.VIEW_PLAN);
        PlanPin pin = pin(site, floorPlanId, pinId);
        requireEdit(site, pin);
        PlanPinPhoto photo = photos.findByIdAndPinId(photoId, pin.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found"));
        if (!pin.isIssue() && photos.countByPinId(pin.getId()) <= 1) {
            throw new RuleViolationException("PIN_PHOTO_REQUIRED", "A progress photo pin needs a photo");
        }
        photos.delete(photo);
        storage.delete(photo.getStorageKey());
        auditService.record(AUDIT_ENTITY, pin.getId(), "PHOTO_DELETE", site.membershipId(),
                Map.of("photoId", photo.getId(), "phase", photo.getPhase()), null);
    }

    @Transactional(readOnly = true)
    public List<AuditEventData> listAudit(SiteScope site, UUID floorPlanId, UUID pinId) {
        site.require(PermissionAction.VIEW_PLAN);
        PlanPin pin = pin(site, floorPlanId, pinId);
        return auditService.listForEntity(AUDIT_ENTITY, pin.getId());
    }

    // ---- rules ----

    /** A live (not deleted) pin of a floor plan of the Site; anything else is a 404. */
    private PlanPin pin(SiteScope site, UUID floorPlanId, UUID pinId) {
        FloorPlan plan = lookup.floorPlan(site, floorPlanId);
        return pins.findByIdAndFloorPlanIdAndDeletedAtIsNull(pinId, plan.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Pin not found"));
    }

    private FloorPlan readyPlan(SiteScope site, UUID floorPlanId) {
        FloorPlan plan = lookup.floorPlan(site, floorPlanId);
        if (!plan.isReady()) {
            throw new RuleViolationException("FLOOR_PLAN_NOT_READY", "Floor plan must be READY to add pins");
        }
        return plan;
    }

    private static boolean isReporter(SiteScope site, PlanPin pin) {
        return pin.getCreatedBy().getId().equals(site.membershipId());
    }

    private static boolean isAssignee(SiteScope site, PlanPin pin) {
        return pin.getAssignee() != null && pin.getAssignee().getId().equals(site.membershipId());
    }

    private static boolean canEdit(SiteScope site, PlanPin pin) {
        return site.can(PermissionAction.MANAGE_PLAN) || (isReporter(site, pin) && pin.isOpenForReporter());
    }

    private static void requireEdit(SiteScope site, PlanPin pin) {
        if (!canEdit(site, pin)) {
            site.require(PermissionAction.MANAGE_PLAN);
        }
    }

    private static PinActions actions(SiteScope site, PlanPin pin) {
        boolean manage = site.can(PermissionAction.MANAGE_PLAN);
        boolean edit = canEdit(site, pin);
        boolean issue = pin.isIssue();
        return new PinActions(
                edit,
                issue && manage,
                issue && pin.getStatus() == PinStatus.OPEN && (manage || isAssignee(site, pin)),
                issue && manage && pin.getStatus() != PinStatus.CLOSED,
                issue && manage && pin.getStatus() != PinStatus.OPEN,
                edit);
    }

    /** Only people with access to the Site can be given an issue, the same people the plan roster offers. */
    private OrganizationMembership siteStaffMember(SiteScope site, UUID membershipId) {
        boolean onSite = siteStaff.staff(site.siteId()).stream()
                .anyMatch(a -> a.membershipId().equals(membershipId));
        if (!onSite) {
            throw new RuleViolationException("PIN_ASSIGNEE_NO_ACCESS",
                    "The assignee must have access to this site");
        }
        return memberships.find(site.organizationId(), membershipId)
                .orElseThrow(() -> new RuleViolationException("PIN_ASSIGNEE_NO_ACCESS",
                        "The assignee must have access to this site"));
    }

    private void flush(PlanPin pin) {
        try {
            pins.saveAndFlush(pin);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw conflict();
        }
    }

    private static RuleViolationException conflict() {
        return new RuleViolationException("PIN_CONFLICT", "Pin was modified by another user; refresh and retry");
    }

    // ---- photos ----

    private static String photoPrefix(SiteScope site, FloorPlan plan) {
        return "orgs/%s/projects/%s/floor-plans/%s/pins/"
                .formatted(site.organizationId(), site.siteId(), plan.getId());
    }

    /**
     * Turns uploaded keys into photos of the pin. A key counts only if it was issued for this plan, holds an
     * image within the limits, and no other photo already uses it.
     */
    private List<PlanPinPhoto> attach(SiteScope site, PlanPin pin, OrganizationMembership actor,
                                      List<String> keys, PhotoPhase phase) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(keys));
        if (distinct.isEmpty()) return List.of();
        if (photos.countByPinId(pin.getId()) + distinct.size() > MAX_PHOTOS_PER_PIN) {
            throw new RuleViolationException("PIN_PHOTO_LIMIT", "A pin holds at most 10 photos");
        }
        String prefix = photoPrefix(site, pin.getFloorPlan());
        List<PlanPinPhoto> attached = new ArrayList<>();
        for (String key : distinct) {
            if (key == null || !key.startsWith(prefix) || photos.existsByStorageKey(key)) {
                throw new RuleViolationException("PIN_PHOTO_INVALID", "The photo does not belong to this floor plan");
            }
            ObjectStorage.StoredObject stored = storage.head(key)
                    .orElseThrow(() -> new RuleViolationException("PIN_PHOTO_INVALID", "The photo was not uploaded"));
            String type = stored.contentType() == null ? "" : stored.contentType().toLowerCase(Locale.ROOT);
            if (stored.sizeBytes() > MAX_PHOTO_BYTES || !PHOTO_TYPES.contains(type)) {
                storage.delete(key);
                throw new RuleViolationException("PIN_PHOTO_INVALID",
                        "Pin photos must be JPEG, PNG or WebP up to 5 MB");
            }
            attached.add(photos.save(new PlanPinPhoto(pin, actor, key, phase, type, stored.sizeBytes())));
        }
        return attached;
    }

    // ---- views ----

    private PinData detail(SiteScope site, PlanPin pin) {
        return view(site, pin.getFloorPlan(), List.of(pin))
                .data(pin, photos.findByPinIdOrderByCreatedAtAsc(pin.getId()), true);
    }

    /** Zones and names for a batch of pins, read once whatever the number of pins. */
    private View view(SiteScope site, FloorPlan plan, List<PlanPin> batch) {
        // Zones are drawn in metres and pins sit in pixels; without a scale the plan has no zones to match
        Double metersPerPx = calibrations.findByFloorPlanId(plan.getId())
                .map(c -> c.getMetersPerPx()).orElse(null);
        List<Area> areas = metersPerPx == null ? List.of() : zones.findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtAsc(plan.getId()).stream()
                .map(z -> new Area(z.getId(), z.getName(), z.parsePoints()))
                .toList();
        Set<UUID> ids = new LinkedHashSet<>();
        for (PlanPin pin : batch) {
            ids.add(pin.getCreatedBy().getId());
            if (pin.getAssignee() != null) ids.add(pin.getAssignee().getId());
        }
        Map<UUID, OrganizationMembership> byId = ids.isEmpty() ? Map.of()
                : memberships.byIds(site.organizationId(), ids).stream()
                        .collect(Collectors.toMap(OrganizationMembership::getId, Function.identity()));
        Map<UUID, Person> persons = directory.byUserId(byId.values().stream()
                .map(m -> m.getUserAccount().getId()).toList());
        return new View(site, metersPerPx == null ? 1 : metersPerPx, areas, byId, persons);
    }

    private record Area(UUID id, String name, List<double[]> points) {
        /** Ray casting: a point on the edge may fall on either side, which is fine for a pin. */
        boolean contains(double x, double y) {
            boolean inside = false;
            for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
                double[] a = points.get(i);
                double[] b = points.get(j);
                if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    private final class View {
        private final SiteScope site;
        private final double metersPerPx;
        private final List<Area> areas;
        private final Map<UUID, OrganizationMembership> memberships;
        private final Map<UUID, Person> persons;

        private View(SiteScope site, double metersPerPx, List<Area> areas,
                     Map<UUID, OrganizationMembership> memberships, Map<UUID, Person> persons) {
            this.site = site;
            this.metersPerPx = metersPerPx;
            this.areas = areas;
            this.memberships = memberships;
            this.persons = persons;
        }

        PinData data(PlanPin pin, List<PlanPinPhoto> pinPhotos, boolean withPhotos) {
            // The first zone drawn wins where zones overlap
            Area area = areas.stream()
                    .filter(a -> a.contains(pin.getX() * metersPerPx, pin.getY() * metersPerPx))
                    .findFirst().orElse(null);
            URI cover = pinPhotos.isEmpty() ? null
                    : storage.presignDownload(pinPhotos.get(0).getStorageKey(), VIEW_TTL);
            List<PhotoData> shown = !withPhotos ? null : pinPhotos.stream()
                    .map(ph -> new PhotoData(ph.getId(), ph.getPhase(),
                            storage.presignDownload(ph.getStorageKey(), VIEW_TTL), ph.getCreatedAt()))
                    .toList();
            return new PinData(pin.getId(), pin.getFloorPlan().getId(), pin.getKind(), pin.getX(), pin.getY(),
                    pin.getTitle(), pin.getDescription(), pin.getStatus(),
                    pin.getAssignee() == null ? null : person(pin.getAssignee().getId()),
                    person(pin.getCreatedBy().getId()),
                    area == null ? null : area.id(), area == null ? null : area.name(),
                    pinPhotos.size(), cover, pin.getVersion(), pin.getCreatedAt(), pin.getResolvedAt(),
                    pin.getClosedAt(), actions(site, pin), shown);
        }

        private PersonRef person(UUID membershipId) {
            OrganizationMembership m = memberships.get(membershipId);
            Person person = m == null ? new Person(null, null)
                    : PersonDirectory.of(persons, m.getUserAccount().getId());
            return new PersonRef(membershipId, person.fullName());
        }
    }

    private static UUID assigneeId(PlanPin pin) {
        return pin.getAssignee() == null ? null : pin.getAssignee().getId();
    }

    private static Map<String, Object> snapshot(PlanPin pin) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", pin.getId());
        state.put("kind", pin.getKind());
        state.put("title", pin.getTitle());
        state.put("description", pin.getDescription());
        state.put("x", pin.getX());
        state.put("y", pin.getY());
        state.put("status", pin.getStatus());
        state.put("assigneeMembershipId", assigneeId(pin));
        return state;
    }
}
