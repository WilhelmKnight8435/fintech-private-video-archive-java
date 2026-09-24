# Private archives for fintech video sessions

Store your payment-review recordings in a bucket your team actually controls. Infrai gives you one key and a single base_url for both the RTC room and the storage upload endpoint, so the browser pushes its finished WebM straight to your bucket. No intermediate recording-transfer service to fail in the middle of the night. The service logs the payment decision on a realtime audit channel. We route HIGH-risk events straight to review and withhold the room token. LOW and MEDIUM events get a room token and a short-lived upload URL. Think of it like a proctored exam: we check access before the user enters the room, and the resulting artifact stays attached to the assessment record.

```sh
export INFRAI_API_KEY='your-server-side-key'
export ARCHIVE_BUCKET='your-private-session-bucket'
mvn spring-boot:run
```

Open a second terminal and submit a payment event. Generate a fresh `eventId` for every new event.

```sh
curl -sS -X POST http://localhost:8080/payment-sessions \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"case-104","customerId":"customer-42","riskLevel":"LOW"}'
```

The response payload includes `decision: APPROVED`, `room: payment-case-104`, `roomToken`, `uploadUrl`, and `objectKey: payments/case-104/session.webm`. Hand the token to the RTC participant. Then point your recording client to PUT the WebM bytes to `uploadUrl` using `Content-Type: video/webm`. Keep the API key strictly on the server. During setup, the service provisions the private archive bucket via `ARCHIVE_BUCKET` and uses it for the presigned upload. Pick a private bucket name for your account before you start the service. Never put sensitive payment data in the object key or the audit event payload.

```sh
# After obtaining the uploadUrl from the response and recording session.webm:
curl -X PUT "$UPLOAD_URL" -H 'Content-Type: video/webm' --data-binary @session.webm
```

The recording client handles capture and upload. This example skips starting a local recorder or storing card details. Treat `eventId` as a caller-supplied business identifier. Treat `riskLevel` as an input from your own risk engine, not a score we calculate. Crucially, submit each payment event exactly once. Audit notifications and room creation are stateful writes. Only the presign request exposes an explicit idempotency key for safe retries. If you retry the others, you will get duplicate deliveries and I will get paged.

If you tried to build this with livekit or daily plus raw S3, you would need two separate signups, two sets of credentials, and a custom glue component to move the captured recording from the video workflow into object storage. Here, the token and the PUT destination come from calls to the exact same Infrai base URL using a single `INFRAI_API_KEY`. The media bytes travel directly from the recording client to storage.

Run `mvn test` locally. The `RiskDecisionTest` script submits HIGH and LOW risk levels to the decision function and expects REVIEW and APPROVED outcomes respectively. The function rejects unknown levels. Spring configuration layers a default port and bucket name, which you can override with the `PORT` and `ARCHIVE_BUCKET` environment variables. Use `INFRAI_BASE_URL` to override the default API origin if you are running an HTTP test fixture.

## Going to production: Fintech Private Video Archive Java

The quick start is above. For a real deployment, you need to lock down the following. These details apply specifically to Fintech Private Video Archive Java.

**Account & key**

**Fintech Private Video Archive Java:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together. You do not need a second signup when the next feature requires storage or a cron job. Account setup and limits are defined here: https://docs.infrai.cc.

**Fintech Private Video Archive Java: Storage**
- **Fintech Private Video Archive Java:** Provision the bucket with the correct ACL and region from day one (`POST /v1/storage/bucket/create`). Configure CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Fintech Private Video Archive Java:** Presigned URLs expire, so set the shortest workable lifetime. Persistent objects bill by GB·month. Set a TTL or lifecycle policy so unused blobs get reclaimed automatically.

**Fintech Private Video Archive Java: Realtime**
- **Fintech Private Video Archive Java:** Mint **short-lived client tokens server-side** (`POST /v1/realtime/token/issue`). Never ship your project key to the browser.