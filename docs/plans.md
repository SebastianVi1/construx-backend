# Site plans

Plans are image files of a site (`Project`) kept in a private S3 bucket. Module `modules/plan`, schema
`V19__project_plans.sql`, permissions `V20__plan_permissions.sql`, storage port `shared/storage/ObjectStorage`.
Bucket and IAM setup: [plans-aws-setup.md](plans-aws-setup.md).

## Upload flow

The file never passes through the API. The browser sends it straight to S3 with a presigned URL:

1. `POST .../plans/uploads` with the file's name, type and size. The API checks permissions and limits,
   stores the plan as `PENDING` and returns a presigned `PUT` URL valid for 10 minutes.
2. The client sends `PUT uploadUrl` with the file as the body and exactly the returned `headers`. No cookies,
   no CSRF header: this request goes to S3, not to the API. S3 rejects a different `Content-Type` or size.
3. `POST .../plans/{planId}/complete`. The API checks that S3 holds a file with the announced size and type and
   turns the plan `READY`.

Lists and reads return a presigned `GET` URL (`viewUrl`) valid for 10 minutes; ask again for a fresh one.

Storage key: `orgs/{organizationId}/projects/{projectId}/plans/{planId}/{random uuid}.{jpg|png|webp}`.
The original file name is stored in the database only.

## Endpoints

Base path `/api/organizations/{organizationId}/projects/{projectId}/plans`. Checks run in this order:
non-member -> 404, unknown site -> 404, missing permission -> 403, plan outside the site -> 404.

| Method and path | Permission | Notes |
|---|---|---|
| `POST /uploads` | `MANAGE_PLAN` | Body `{fileName, contentType, sizeBytes, name?, description?}`. 201 `{planId, uploadUrl, headers, expiresAt}`. |
| `POST /{planId}/complete` | `MANAGE_PLAN` | 200 `PlanResponse`. No file -> 400 `PLAN_UPLOAD_MISSING`; other size or type -> 400 `PLAN_UPLOAD_MISMATCH`; already ready -> 409 `STATE_CONFLICT`. |
| `GET /` | `VIEW_PLAN` | `READY` plans, newest first. 200 array of `PlanResponse`. |
| `GET /{planId}` | `VIEW_PLAN` | 200 `PlanResponse`; a `PENDING` plan -> 404. |
| `DELETE /{planId}` | `MANAGE_PLAN` | Deletes the row, then the file. 204. Also works on `PENDING` plans. |

`PlanResponse`:
```json
{ "id": "uuid", "projectId": "uuid", "name": "Ground floor", "description": null,
  "fileName": "Ground floor.png", "contentType": "image/png", "sizeBytes": 2048,
  "createdAt": "2026-10-28T18:00:00Z", "completedAt": "2026-10-28T18:00:05Z",
  "viewUrl": "https://...", "viewUrlExpiresAt": "2026-10-28T18:10:05Z" }
```

## Rules

- `contentType`: `image/jpeg`, `image/png` or `image/webp` (case-insensitive), else 400 `PLAN_FILE_TYPE`.
- `sizeBytes`: 1 byte to 20 MB (20 971 520 bytes), else 400 `PLAN_FILE_TOO_LARGE` (0 or missing -> 400 `VALIDATION_FAILED`).
- `fileName`: 1 to 255 characters. `name`: up to 120 characters; blank or missing uses the file name without its
  extension, cut to 120. `description`: up to 500 characters, blank stores `null`.
- A plan that never completes stays `PENDING` and is hidden from lists. A cleanup job is pending; the bucket's
  lifecycle rule removes abandoned multipart uploads.
- If S3 fails while deleting, the request fails with 500 and the plan is kept.

## Configuration

| Variable | Use |
|---|---|
| `AWS_BUCKET_NAME` | Bucket name (required) |
| `AWS_REGION` | Bucket region, default `us-east-1` |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | Keys of the backend IAM user, or set `AWS_PROFILE` instead |

Credentials come from the AWS SDK default chain and are only resolved on the first S3 call, so the app starts
without them. Tests replace `ObjectStorage` with a mock.
