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
