# User profile completion
Objective: Implement `.agents/specs/SDD-01.complete-user-profile.md` without Flyway.
Branch: `feat/user-profile`; delivery: ask-on-risk; runner: `./mvnw test`.

## Specs
S1. "A user can complete and retrieve personal information independently of membership in an organization or project." Use a separate optional one-to-one `UserProfile`; existing accounts without a profile remain valid.
S2. "Keep email normalization and uniqueness." "Do not store passwords in the profile." "Do not create an organization, membership, project, or worker profile when personal information is completed." "Do not expose sensitive information in API responses."
S3. "Validate and normalize phone numbers according to an agreed policy; do not invent a format that excludes international numbers." The user authorized everything except Flyway after the proposed policy: require `+` country code and store E.164.
S4. "If a new entity is introduced, define its cardinality, foreign key, and account-deletion behavior." Profile is optional one-to-one; its account ID is a unique FK and profile rows delete with the account.
S5. "An authenticated user can retrieve their personal information." "The user can update permitted fields." "Invalid input returns consistent validation errors." "A user cannot read or modify another user's profile by changing a UUID." "DTOs do not expose JPA entities directly."
S6. Required tests: "Successful retrieval and update." "Missing or invalid fields." "Unauthenticated request." "Attempt to modify another user's profile." "Migration with existing accounts." No Flyway or migration artifact by explicit follow-up; verify existing-account compatibility with additive optional schema and document live-upgrade proof as pending.
S7. Out of scope: "Creating WorkerProfile." "Creating organizations or projects." "Changing the authentication mechanism unless strictly required."
S8. Deliverables: "Necessary code and DTOs." "Automated tests." "A brief architectural decision note." No Flyway by explicit follow-up.

## Tasks
- [x] T1 (S1-S8): inline; profile model, authenticated self endpoints, validation, tests, decision note; high-risk items 1 and 2; commit `feat(profile): add self-service personal profile`.

## Log
L1 (2026-10-23): "Use Specific Driven Development and ipmelent the ./.agents/specs/SDD_01 task in the code."
L2 (2026-10-23): "Implement everything but flyway with no flyway"
L3 (2026-10-23): Spec file is `.agents/specs/SDD-01.complete-user-profile.md`. Existing worktree contains staged and unstaged user edits; preserve them. Current production setting is `spring.jpa.hibernate.ddl-auto=update`; tests use H2 `create-drop`. No existing name/phone fields or migration scripts.
L4 (2026-10-23): Chose separate optional profile rather than adding personal data to `UserAccount`. Trade-off: one extra table/query in exchange for credential/profile separation and compatibility with existing accounts. Phone policy: internationally prefixed E.164; no default country inference.
L5 (2026-10-23): RED could not reach the new test because the live worktree already fails compilation: an unrelated dirty `PermissionScope.java` removes `ORGANIZATION`; default `java` lacks `javac`. A full JDK at `/home/sebas/.jdks/ms-25.0.4.1` was used. In isolated scratch, replacing only that unrelated file with its HEAD version, focused tests passed (5/5) and full suite passed (30/30), no errors/skips. Independent verifier observed the same. Live PostgreSQL schema upgrade and rollback remain unverified; no Flyway artifact was added.
