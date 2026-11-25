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

## WhatsApp (Linked number)

A user links one WhatsApp number so they can send Field updates (contract `.agents/contracts/field-updates.md` §2, §4.1, §5). Code: `modules/messaging` (`WhatsAppLinkService`, `WhatsAppProfileController`); migration `V45__whatsapp_link.sql` (`whatsapp_link`, `whatsapp_link_code`).

- `GET /api/profile/whatsapp` → `{linked, number, language, linkedAt, construxNumber}`. `number` is the full `+`-prefixed number (the dashboard masks it); `number`, `language` and `linkedAt` are `null` when not linked. `construxNumber` is `WHATSAPP_DISPLAY_NUMBER`.
- `POST /api/profile/whatsapp/link-code` with `{language: "es"|"en"}` → `201 {code, expiresAt, construxNumber, waMeUrl}`. The code is `CX-` plus 4 digits, valid 10 minutes and once; only its SHA-256 is stored, and a new code replaces the old one. No two users hold the same active code. `waMeUrl` is `https://wa.me/<construx digits>?text=<code>`.
- `PUT /api/profile/whatsapp/language` with `{language}` → same body as `GET`; `404 WHATSAPP_NOT_LINKED` when not linked.
- `DELETE /api/profile/whatsapp` → `204`, also when nothing is linked.

From WhatsApp, a sender who sends a code (trimmed, any case) is linked: the bot replies `LINKED` with the person's name in the code's language, and a number the user had before is replaced. This also works from a number still linked to someone else (a recycled number): the number moves to the code's owner, the previous owner loses their link and is emailed in the language of the link they lost (only the last 4 digits are shown), and their audit trail records `MOVE`. A wrong, used or expired code, or a disabled owner, gets `CODE_INVALID` in both languages. After 10 wrong codes from one number within an hour (the hour starts at the first wrong code), every message from that number is ignored, without a reply, for an hour after the 10th. `BAJA` or `STOP` (any case) from a Linked number unlinks it and replies `UNLINKED`; the next message gets `NOT_LINKED`. A disabled account's number is treated as unlinked. Audit: entity `WHATSAPP_LINK`, entity id = the user's account id, actions `LINK` / `UNLINK` / `MOVE`, state `{number: "…1234", language, source: "WHATSAPP"|"APP"}`. `MOVE` is recorded on the previous owner, with their link as the before-state and the new link plus `movedToUserId` as the after-state. Expired codes and wrong-code counts that no longer block are purged by the daily WhatsApp cleanup.
