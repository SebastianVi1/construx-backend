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

Authorization is evaluated per request through `DomainAuthorization`; roles are never read from the JWT.

## Seeded roles

Every new organization gets `OWNER` plus the roles defined in `src/main/resources/seeded-roles.properties` (default: `ADMINISTRATOR`, `MEMBER`, `WORKER`, `FINANCE`). Edit that file to change names or permissions; invalid action/scope pairs fail at startup. Changes apply only to organizations created afterwards. Matrix and rationale: `.agents/contracts/v1-api.md` section 8.

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
