Problem
UserAccount contains identity and authentication data, but the intended registration flow also requires a name and phone number. Decide where these fields belong without mixing credentials with personal information or duplicating the future worker profile.
Expected outcome
A user can complete and retrieve personal information independently of membership in an organization or project.
Before implementation
1. Inspect UserAccount, registration DTOs, AccountService, repositories, and existing migrations.
2. Check whether name or phone fields already exist in another entity.
3. Propose a concise decision: extend UserAccount or create UserProfile. Choose the smallest option that preserves clear responsibilities, and explain the trade-offs before changing the schema.
Requirements
- Keep email normalization and uniqueness.
- Do not store passwords in the profile.
- Validate and normalize phone numbers according to an agreed policy; do not invent a format that excludes international numbers.
- Do not create an organization, membership, project, or worker profile when personal information is completed.
- Do not expose sensitive information in API responses.
- If a new entity is introduced, define its cardinality, foreign key, and account-deletion behavior.
Acceptance criteria
- [ ] An authenticated user can retrieve their personal information.
- [ ] The user can update permitted fields.
- [ ] Invalid input returns consistent validation errors.
- [ ] A user cannot read or modify another user's profile by changing a UUID.
- [ ] Existing accounts remain valid after migration.
- [ ] DTOs do not expose JPA entities directly.
Required tests
- Successful retrieval and update.
- Missing or invalid fields.
- Unauthenticated request.
- Attempt to modify another user's profile.
- Migration with existing accounts.
Out of scope
- Creating WorkerProfile.
- Creating organizations or projects.
- Changing the authentication mechanism unless strictly required.
Deliverables
- Necessary code and DTOs.
- Flyway migration, if applicable.
- Automated tests.
- A brief architectural decision note.