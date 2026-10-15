# Authentication and Authorization

This guide describes the current backend contract and the frontend work needed to use it. The browser receives HttpOnly cookies; JavaScript does not store or read JWTs.

## How authentication works

1. A password account registers, receives an email link, and verifies its address before signing in. Verification links expire after 24 hours and work once. SMTP is used for these emails; password recovery is not implemented.
2. Password login or Google OpenID Connect login creates an `auth_session` database row. Both methods then issue the **application's** `access_token` JWT (15 minutes) and opaque `refresh_token` (30 days) as cookies. Google's tokens are not used to call this API.
3. On each protected request, Spring validates the JWT and the backend checks the session row and user status. The API uses no HTTP session for normal requests, but this database lookup makes login revocable; it is not fully stateless. Google OAuth uses a temporary HTTP session to correlate the redirect.
4. Refresh replaces both cookies and rotates the refresh secret. Reusing an old refresh secret revokes that session. Logout revokes the session and clears the cookies. Changing a local password revokes **all** sessions.

## Backend and browser requirements

- Set `FRONTEND_URL` to the frontend origin, for example `http://localhost:3000`. The backend uses it for email links and Google success/failure redirects. Set the other required values listed in `docs/security.md` (database, JWT secret, Google OAuth client, and Gmail SMTP).
- Register the backend callback URL with Google: `http://localhost:8080/login/oauth2/code/google` for the default local backend address. This is **not** a frontend page. The OAuth start URL is `http://localhost:8080/oauth2/authorization/google`.
- Auth cookies are `HttpOnly`, `Secure`, and `SameSite=Lax`. The refresh cookie is restricted to `/api/auth`. Use HTTPS outside localhost and keep frontend and API on the same site with the current cookie policy. Never put the JWT or refresh token in local storage.
- Browser requests from `localhost:3000` to `localhost:8080` are cross-origin. This backend currently has **no CORS configuration**. Before using direct cross-origin `fetch`, configure credentialed CORS for the exact frontend origin on the backend. Alternatively, use a same-origin development/reverse proxy for API and OAuth routes; make its Google callback URL match the URL registered with Google. A frontend change alone cannot fix CORS.
- Send cookies with every API request (`credentials: "include"` for cross-origin `fetch`). Obtain the CSRF token from `GET /api/auth/csrf`; send the returned `headerName` and `token` on every `POST`, `PUT`, `PATCH`, and `DELETE`, including public auth calls. A CSRF cookie by itself is insufficient. Refetch the CSRF token if it is missing or rejected.

## Endpoint contract

All paths below are on the **backend**. JSON requests use `Content-Type: application/json`.

| Action | Request | Success |
| --- | --- | --- |
| Get CSRF | `GET /api/auth/csrf` | `200` with `{ "headerName", "token" }` and `XSRF-TOKEN` cookie |
| Register | `POST /api/auth/register` with `{ "email", "password" }` | `202`; password must have at least 12 characters |
| Resend verification | `POST /api/auth/resend` with `{ "email" }` | `202` |
| Verify email | `POST /api/auth/verify` with `{ "token" }` | `204` |
| Password login | `POST /api/auth/login` with `{ "email", "password" }` | `204` and auth cookies |
| Current user | `GET /api/auth/me` | `200` with `{ "id", "email" }` |
| Refresh | `POST /api/auth/refresh` | `204` and replacement auth cookies |
| Logout | `POST /api/auth/logout` | `204` and cleared auth cookies |
| Change password | `POST /api/auth/password` with `{ "currentPassword", "newPassword" }` | `204`; sign in again afterward |
| Start Google login | Browser navigation to `GET /oauth2/authorization/google` | Google redirects back to the backend; backend sets cookies and redirects to frontend |
| Link Google account | Authenticated `POST /api/auth/google/link`, then navigate to returned `authorizationUrl` on the backend | Links only to a verified password account with the same email |

Expect `401` for missing/invalid authentication or an unusable verification token, and `403` when CSRF is missing or access is denied. Registration and resend return `202` even if the email already exists.

## Frontend pages and request flow

Create `/register`, `/login`, `/verify-email`, and `/auth/complete` pages. A password change screen is needed only if you expose that feature.

1. **Register:** fetch CSRF, submit the form, then show a “check your email” state. Offer resend verification. Do not treat registration as login.
2. **Verify email:** read `token` from `/verify-email?token=...`, submit it to `/api/auth/verify` with CSRF, and show success or an expired/used-link error. Then offer login. Avoid sending the one-time token twice.
3. **Password login:** submit credentials with CSRF. The response has no user JSON; call `/api/auth/me` afterward to populate frontend state.
4. **Google login:** navigate the browser to the backend OAuth start URL. On success the backend redirects to `${FRONTEND_URL}/auth/complete`; that page calls `/api/auth/me`, then navigates into the app. On failure the backend redirects to `${FRONTEND_URL}/login?error=google`; show an error there. To link an existing account, sign in with its password first, call `/api/auth/google/link` with CSRF, then navigate to the returned backend-relative URL.
5. **Session handling:** on app load, call `/api/auth/me`. When it returns `401`, try `/api/auth/refresh` once with CSRF, then retry `/me`. If refresh fails, clear frontend user state and show login. Logout uses CSRF, then clears frontend state. Do not retry refresh indefinitely.

A minimal request helper for a same-origin proxy (where `/api` reaches this backend):

```js
async function authPost(path, body) {
  const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' });
  if (!csrfResponse.ok) throw new Error('Could not obtain CSRF token');
  const { headerName, token } = await csrfResponse.json();
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', [headerName]: token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`Request failed: ${response.status}`);
  return response;
}

await authPost('/api/auth/login', { email, password });
const me = await fetch('/api/auth/me', { credentials: 'include' });
```

## Authorization status and testing

All non-public API requests currently require a valid login. `DomainAuthorization` can check active organization membership, role permission, project access, and permission scope, but **no business controller currently invokes it**. Do not treat organization or project permissions as enforced by the HTTP API yet.

For a local check: get CSRF, register, open the emailed link, verify, log in, then call `GET /api/auth/me` and `GET /api/test`. Omit CSRF from a POST to confirm `403`; log out and confirm `/api/auth/me` returns `401`. For Google, test in a browser with a valid OAuth client and its exact registered callback. Automated H2 tests with mocked email: `bash mvnw -Dtest=AuthFlowTest,DomainAuthorizationTest test`.

Spring reference: [OAuth login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html), [CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html).
