# Auth hardening (Spring Security, KISS, TDD)
Objective: fix auth/authz bugs found in review. Branch: fix/auth-hardening. Delivery: ask-on-risk. Runner: `./mvnw test`. Plan: /home/sebas/.claude/plans/create-a-plan-to-curried-clarke.md

## Specs
- S1: Non-test profiles must not use `create-drop`/`show-sql=true`; only the test profile does.
- S2: CORS works for `app.security.frontend-url` with credentials; dead `CorsConfigurer` removed.
- S3: Rate limiting on `/api/auth/login|register|resend|verify|refresh` (429); `/resend` invalidates older verification tokens.
- S4: No `ex.getMessage()` leakage for IllegalState (IllegalArgument domain messages kept by design); mail failure never changes the register response.
- S5: CSRF failure returns the JSON 403 handler body; XSRF cookie Secure/SameSite.
- S6: A pending Google link (`linkUserId`) expires with a 10 minute session. Pre-account hijacking is NOT fixed: letting the latest registrant replace the password (tried, reverted after native review) lets an attacker who registers after the victim win. Needs a product decision: require the password at verification time.
- S7: Refresh double-submit inside a short grace window does not revoke the session.
- S8: Small fixes: log in GoogleLoginHandler catch, actuator health permitAll, password max length 72 bytes, expired session cleanup.
- S9: dead `authorities("USER")` removed. `@PreAuthorize` wiring of `DomainAuthorization` deferred until org/project controllers exist (no consumer yet, YAGNI).
- S10: KISS refactors without behavior change (isActive, cookie constants, login to service, etc.).

## Tasks
- [x] T1 S1 inline (3cc2883; also fixed AuthFlowTest baseline failure via management.health.mail.enabled=false in test profile)
- [x] T2 S2 inline (see git log: fix(security): wire CORS)
- [x] T3 S3 inline (in-memory fixed-window filter, 429 + Retry-After)
- [x] T4 S4 inline (generic 409 detail; mail failures logged)
- [x] T5 S5 inline (forward-headers-strategy left as deployment note)
- [x] T6 S6 inline (link session TTL; pre-account hijacking deferred: naive fix reverted, needs a contract decision)
- [x] T7 S7 inline (HIGH RISK item 1: V2 migration; 10s grace, access-token only; no sliding expiry)
- [x] T8 S8 inline (logout-without-cookie left: access token lives max 15 min)
- [x] T9 S9 inline
- [x] T10 S10 inline (isActive, WebUtils, currentUserId, cookie lifetimes; login-to-service move NOT done)

## Log
- L1 (2026-10-25): "Create a plan to review the actual implementation of authentication and authorization, Oauth2, jwt, csrf, search for bugs and improvements ... spring-security using kiss principles and writing clean code"; then "/springboot-security check the plan above and follow his instructions creating task and compleating it with test driven development"
- Route: all inline (no writer trigger: units sequentially dependent on SecurityConfig).
