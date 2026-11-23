# Organizations

All endpoints require the access cookie. Unsafe methods also require CSRF (see `auth.md`). The caller is always the authenticated account; no account id is accepted from the client.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/organizations` | any verified account | 201, caller becomes OWNER |
| GET | `/api/organizations` | active membership | 200, organizations the caller belongs to |
| GET | `/api/organizations/{organizationId}` | active membership | 200 |
| PATCH | `/api/organizations/{organizationId}` | `MANAGE_ORGANIZATION` | 200, renamed organization |

Body for POST and PATCH: `{"name": "..."}`, 1 to 160 characters after trimming.

## Errors

- A caller without an active membership receives `404 NOT_FOUND`, identical to a missing organization, so identifiers cannot be enumerated.
- An active member without the required permission receives `403 FORBIDDEN`.
- Invalid names return `400 VALIDATION_FAILED` with an `errors` map.

Authorization is evaluated per request through the caller's `OrgScope` / `SiteScope` (`modules/organization/access`, `modules/project/access`), built from the current membership; roles are never read from the JWT.

## Built-in roles and the permission catalog

Every company has the Owner role plus the Built-in roles defined in `src/main/resources/built-in-roles.yml` (`ADMINISTRATOR`, `MEMBER`, `WORKER`, `FINANCE`, `RESIDENT`, `SITE_MANAGER`, `SUPERINTENDENT`), each with EN/ES names, EN/ES descriptions and default grants. The file's `owner:` entry holds the Owner role's names; Owner always holds every action at `ORGANIZATION`. The stored role name is the ES name; the API sends both names from the file (see role summaries below). Rules (code format, no `OWNER` or `CUSTOM_` codes, unique codes and names, names 1–100 characters, both languages present, grants valid per contract roles §4) are checked at startup and an invalid file stops the boot. Changes apply to companies created afterwards; existing companies get them from the startup sync (contract roles §6.2, not yet built), and old grants were backfilled by Flyway (`V11`, `V13`, `V20`, `V23`). Code: `BuiltInRoles`.

The permission catalog is the structure in `PermissionAction` (group, allowed scopes, requirements) plus the EN/ES labels in `src/main/resources/permission-catalog.yml`; a missing label also stops the boot. `PermissionRules.checkScope` / `checkSet` enforce contract roles §4: a scope the catalog does not allow is `400 ROLE_SCOPE_INVALID`, a missing requirement is `400 ROLE_DEPENDENCY_MISSING` with `action` and `requires` in the problem body.

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/api/permissions/catalog` | any authenticated user | `{ groups: [{ key, names }], actions: [{ action, group, names, descriptions, scopes, requires: [{ action, minScope }] }] }` in catalog order; `minScope` is `SAME`, `PROJECT` or `ORGANIZATION` (contract roles §5.10) |

Coded 403s: business rules that refuse an otherwise permitted operation throw `ForbiddenRuleException(code, message)` (`shared/exception`), returned as `403` with that `code` (e.g. `ROLE_ABOVE_CALLER`, `ROLE_OWN`). Plain permission failures stay `403 FORBIDDEN`.

## Onboarding intent

`GET /api/onboarding` and `PUT /api/onboarding` (any authenticated account, own data only) store `{ "intent": "RUN_COMPANY" | "WORKER_OR_INVITED", "businessType": null | "OWNER_DEVELOPER" | "GENERAL_CONTRACTOR" | "ARCHITECT_ENGINEER" | "SUBCONTRACTOR" }`. `intent` is required on PUT; an unknown value returns `400 MALFORMED_BODY`. A user with no answer gets all-null fields, not 404. The answer is a UI hint: it creates no organization and grants no permission.

## Invitations

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/organizations/{organizationId}/invitations` | `MANAGE_MEMBERS` (+ `MANAGE_PROJECT` when `projectId` is set) | 201 invitation |
| GET | `/api/organizations/{organizationId}/invitations?status=PENDING` | `MANAGE_MEMBERS` | 200 list |
| DELETE | `/api/organizations/{organizationId}/invitations/{invitationId}` | `MANAGE_MEMBERS` | 204 |
| POST | `/api/invitations/accept` | any verified account | 200, joins the organization (and the site) |

Create body: `{ "email", "roleCode", "projectId" (optional) }`. `OWNER` cannot be invited. Inviting an address that is already a member returns `409 STATE_CONFLICT`; inviting an address with a pending invitation revokes the old one (this is how to resend). The raw token is only emailed (`/invitations/accept?token=...`); only its SHA-256 hash is stored and it never appears in a response. Invitations expire after `app.invitations.ttl` (default `7d`).

Accept body: `{ "token" }`. The signed-in account's email must equal the invited email; any other account, or an unknown token, gets `404`. Used, revoked or expired tokens and existing members get `409 STATE_CONFLICT`. Accepting creates the membership and, when the invitation names a site, the site access, in one transaction; concurrent accepts of one token produce one membership. The route is rate limited like the other token endpoints.

## My role, roles and members

Every organization response includes `myRole: { code, name, names: { en, es } }`, the caller's role there. Every role summary (`myRole`, `/me` `role`, members, invitations, invitation accept and site access `role`) has this shape: `name` is the stored name, `names` the display name per language (from `built-in-roles.yml` for Owner and Built-in roles; the stored name in both languages for a custom role). Code: `RoleNames.of(role)`.

Roles list fields: `kind` is `OWNER`, `BUILT_IN` or `CUSTOM`; `description` is `{ en, es }` (from the file for Owner and Built-in roles, the stored description in both languages for a custom role, both null when it has none); `system` is true for Owner and Built-in roles (the name is fixed); `memberCount` counts `ACTIVE` and `SUSPENDED` memberships; `pendingInvitationCount` counts `PENDING` invitations (expired ones included, as in the invitations list); `version` is the optimistic-lock version. `editable` and `assignable` are the caller's answers from `RoleAuthority` (contract roles §3): a role *fits within* the caller when the caller's role holds each of its actions at the same or a wider scope (`OWN < PROJECT < ORGANIZATION`), and the Owner role fits only within the Owner. `editable` needs `MANAGE_ROLES`, not the Owner role, not the caller's own role, and the role fits; `assignable` needs `MANAGE_MEMBERS`, not the Owner role, and the role fits.

The member's getting-started guide progress API is documented in [guide.md](guide.md).

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/api/organizations/{organizationId}/me` | active membership | `{ membershipId, role, permissions: [{ action, scope }] }` for gating the UI; the server still checks every request |
| GET | `/api/organizations/{organizationId}/roles` | `MANAGE_ROLES` or `MANAGE_MEMBERS` | `[{ id, code, kind, name, names, description, system, customized, memberCount, pendingInvitationCount, editable, assignable, version }]`, ordered Owner, Built-in roles in file order, custom roles by name (contract roles §5.1) |
| GET | `/api/organizations/{organizationId}/roles/{code}` | `MANAGE_ROLES` or `MANAGE_MEMBERS` | the list object plus `permissions: [{ action, scope }]` in catalog order and `defaults` (the file's set for a Built-in role, else null); unknown code `404` (§5.2) |
| GET | `/api/organizations/{organizationId}/members?status=ACTIVE` | `MANAGE_MEMBERS` | `[{ membershipId, userId, email, fullName, role, status }]`; `fullName` is null without a profile |
| PATCH | `/api/organizations/{organizationId}/members/{membershipId}` | `MANAGE_MEMBERS` | 200, the member with the new role |

PATCH body: `{"roleCode": "ADMINISTRATOR"}`. The owner role cannot be granted (`400`), and the owner's membership cannot get another role (`409`). Changing your own role returns `403`; an unknown or missing role returns `400`, and an unknown membership returns `404`.
