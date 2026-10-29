# Plans bucket setup with the AWS CLI

Sets up the private bucket for [site plans](plans.md). Replace the placeholders first:

```bash
export BUCKET=<bucket-name>          # e.g. construx-plans-prod
export REGION=<region>               # e.g. us-east-1
export PROD_ORIGIN=<https://app.example.com>
```

Run the commands with an admin profile (`aws sts get-caller-identity` shows which one), not with the backend user.

## 1. Bucket (skip if it already exists)

```bash
# us-east-1 takes no LocationConstraint
aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION"
```

## 2. Block all public access

Plans are only read through presigned URLs.

```bash
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

## 3. Default encryption

```bash
aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

## 4. CORS

The browser uploads straight to S3, so the bucket must accept `PUT` and `GET` from the dashboard origins.

```bash
cat > cors.json <<JSON
{
  "CORSRules": [{
    "AllowedOrigins": ["http://localhost:5173", "$PROD_ORIGIN"],
    "AllowedMethods": ["PUT", "GET"],
    "AllowedHeaders": ["Content-Type"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }]
}
JSON
aws s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration file://cors.json
```

## 5. Lifecycle

Removes multipart uploads abandoned for a day. `PENDING` plans rows are not cleaned yet.

```bash
aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration \
  '{"Rules":[{"ID":"abort-incomplete-uploads","Status":"Enabled","Filter":{"Prefix":""},
    "AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}}]}'
```

## 6. Backend IAM user

Only object read, write and delete under `orgs/`, plus listing the bucket: that lets `HeadObject` answer 404 for a file
that was never uploaded (without it S3 answers 403 and the backend cannot tell a missing file from a denied one).

```bash
aws iam create-user --user-name construx-backend

cat > plans-policy.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::$BUCKET/orgs/*"
  }, {
    "Effect": "Allow",
    "Action": "s3:ListBucket",
    "Resource": "arn:aws:s3:::$BUCKET"
  }]
}
JSON
aws iam put-user-policy --user-name construx-backend --policy-name construx-plans \
  --policy-document file://plans-policy.json

aws iam create-access-key --user-name construx-backend
```

Put the returned keys in the backend environment, never in the repository:

```bash
export AWS_ACCESS_KEY_ID=<AccessKeyId>
export AWS_SECRET_ACCESS_KEY=<SecretAccessKey>
export AWS_REGION="$REGION"
export AWS_BUCKET_NAME="$BUCKET"
```

## 7. Check

```bash
aws s3api get-public-access-block --bucket "$BUCKET"
aws s3api get-bucket-cors --bucket "$BUCKET"
aws s3api get-bucket-encryption --bucket "$BUCKET"
```

End to end, with the backend running and a session cookie in the browser:

1. `POST /api/organizations/{orgId}/projects/{projectId}/plans/uploads` with
   `{"fileName":"test.png","contentType":"image/png","sizeBytes":<bytes of test.png>}`.
2. Send the file to the returned URL:
   ```bash
   curl -X PUT -H 'Content-Type: image/png' --data-binary @test.png "<uploadUrl>"
   ```
3. `POST .../plans/{planId}/complete`, then list the bucket:
   ```bash
   aws s3 ls "s3://$BUCKET/orgs/" --recursive
   ```
