# User profile decision

Personal information lives in an optional `UserProfile`, not in `UserAccount` or a worker profile. This keeps password hashes and authentication state separate from name and phone details. The trade-off is an extra table and lookup.

The relationship is one profile per account, keyed by `user_account_id`; that column is both the primary key and a foreign key to `user-account.id`. The foreign key uses `ON DELETE CASCADE`, so deleting an account also deletes its profile. An account may exist without a profile, including accounts created before this feature.

`GET /api/profile` returns `fullName` and `phoneNumber`, both `null` until completion. `PUT /api/profile` replaces both fields for the authenticated account; the API accepts no account UUID, and responses contain no JPA entity or credential. Full names are trimmed. Phone input must have a `+` country code and E.164 syntax (2–15 digits after `+`); surrounding whitespace is trimmed. The syntax rule does not prove that a number is assigned or reachable.

No Flyway migration is added, per the requested scope. Hibernate's existing `ddl-auto=update` setting is expected to add the optional table. The H2 upgrade test checks an existing account survives this additive change, but a PostgreSQL upgrade and rollback still require a disposable database rehearsal before deployment.

## Trade

The profile also has an optional `trade` (free text, at most 80 characters, e.g. `"electrician"`), returned by `GET` and accepted by `PUT /api/profile`. It describes what the person does and grants no permission. A `PUT` without `trade` keeps the stored value, so existing clients cannot erase it; send `"trade": ""` to clear it. Migration: `V8__profile_trade.sql`.

## Photo

The profile has an optional photo stored in the private bucket under `users/<accountId>/avatar/<uuid>`; the column `avatar_key` holds the key (migration `V35__profile_avatar.sql`). `GET`/`PUT /api/profile` return `avatarUrl`, a presigned download URL valid for one hour, or `null`.

1. `POST /api/profile/avatar/uploads` with `{contentType, sizeBytes}` → `201 {key, uploadUrl, headers, expiresAt}`. Only `image/jpeg`, `image/png` and `image/webp` up to 5 MB (`AVATAR_TYPE_UNSUPPORTED`, `AVATAR_TOO_LARGE`).
2. The client `PUT`s the bytes to `uploadUrl` with `headers`, without cookies.
3. `PUT /api/profile/avatar` with `{key}` → the profile. The key must start with the caller's own prefix (`AVATAR_KEY_INVALID`) and exist in storage (`AVATAR_UPLOAD_MISSING`); the stored type and size are checked again. The previous photo is deleted.

`DELETE /api/profile/avatar` → `204`, removes the photo. Both writes need a saved profile (`PROFILE_REQUIRED`). A presigned upload that is never confirmed leaves an orphan object; there is no cleanup job yet.
