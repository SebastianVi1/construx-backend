# User profile decision

Personal information lives in an optional `UserProfile`, not in `UserAccount` or a worker profile. This keeps password hashes and authentication state separate from name and phone details. The trade-off is an extra table and lookup.

The relationship is one profile per account, keyed by `user_account_id`; that column is both the primary key and a foreign key to `user-account.id`. The foreign key uses `ON DELETE CASCADE`, so deleting an account also deletes its profile. An account may exist without a profile, including accounts created before this feature.

`GET /api/profile` returns `fullName` and `phoneNumber`, both `null` until completion. `PUT /api/profile` replaces both fields for the authenticated account; the API accepts no account UUID, and responses contain no JPA entity or credential. Full names are trimmed. Phone input must have a `+` country code and E.164 syntax (2–15 digits after `+`); surrounding whitespace is trimmed. The syntax rule does not prove that a number is assigned or reachable.

No Flyway migration is added, per the requested scope. Hibernate's existing `ddl-auto=update` setting is expected to add the optional table. The H2 upgrade test checks an existing account survives this additive change, but a PostgreSQL upgrade and rollback still require a disposable database rehearsal before deployment.
