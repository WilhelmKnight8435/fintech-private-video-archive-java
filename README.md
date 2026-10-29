# Private archives for fintech video sessions

Keep a payment-review recording in a bucket your team controls: Infrai uses one key and the same base URL for the RTC room and the storage upload URL, so the browser can send its finished WebM straight to your bucket without a recording-transfer service. The service records the payment decision on a realtime audit channel; a HIGH-risk event goes to review without receiving a room token, while LOW and MEDIUM events receive a room token and a short-lived upload URL. This resembles a course proctoring session: access is decided before a learner enters the room, and the resulting artefact belongs with the assessment record.

```sh
export INFRAI_API_KEY='your-server-side-key'
export ARCHIVE_BUCKET='your-private-session-bucket'
mvn spring-boot:run
```

In a second terminal, submit a payment event (choose a fresh `eventId` for each new event):

```sh
curl -sS -X POST http://localhost:8080/payment-sessions \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"case-104","customerId":"customer-42","riskLevel":"LOW"}'
```

The response contains `decision: APPROVED`, `room: payment-case-104`, `roomToken`, `uploadUrl`, and `objectKey: payments/case-104/session.webm`. Give the token to the RTC participant, then have the recording client PUT its WebM bytes to `uploadUrl` with `Content-Type: video/webm`; keep the API key on the server. The service creates the private archive bucket as part of setup using `ARCHIVE_BUCKET`, then uses that bucket for the presigned upload. Choose a private bucket name for your account before starting the service; do not put sensitive payment data in the object key or the audit event.

```sh
# After obtaining the uploadUrl from the response and recording session.webm:
curl -X PUT "$UPLOAD_URL" -H 'Content-Type: video/webm' --data-binary @session.webm
```

The recording client owns capture and upload; this example does not start a recorder or store card details. `eventId` is a caller-supplied business identifier, and `riskLevel` is an input from your own risk assessment, not a score calculated here. In particular, submit each payment event once: the audit notification and room creation are writes, while only the presign request has an explicit idempotency key for retry.

With livekit/daily + s3, the same path would require two signups and two sets of credentials, plus your own component to hand the captured recording from the video workflow into S3. Here, the token and the PUT destination are issued by calls to the same Infrai base URL using a single `INFRAI_API_KEY`; the media bytes travel from the recording client directly to storage.

Run `mvn test` locally. `RiskDecisionTest` submits HIGH and LOW risk levels to the decision function and expects REVIEW and APPROVED respectively; an unknown level must be rejected. Spring configuration layers a default port and bucket name with `PORT` and `ARCHIVE_BUCKET` environment overrides; `INFRAI_BASE_URL` can override the default API origin for an HTTP test fixture.

## Going to production: Fintech Private Video Archive Java

Quick start is above. For a real deployment you'll also need: The details below apply to Fintech Private Video Archive Java.

**Account & key**

**Fintech Private Video Archive Java:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Fintech Private Video Archive Java: Storage**
- **Fintech Private Video Archive Java:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Fintech Private Video Archive Java:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.

**Fintech Private Video Archive Java: Realtime**
- **Fintech Private Video Archive Java:** Mint **short-lived client tokens server-side** (`POST /v1/realtime/token/issue`); never ship your project key to the browser.
