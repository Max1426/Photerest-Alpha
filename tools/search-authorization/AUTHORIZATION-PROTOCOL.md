# Search authorization protocol v1

All signatures use RSA-2048, SHA-256, RSASSA-PKCS1-v1_5. Binary values use unpadded base64url. All signed strings use UTF-8, LF separators, and no trailing LF. Only the offline owner signer has the authority private key.

## Offline request and certificate

The exported UTF-8 JSON request (at most 16 KiB) has `v:1`, `app`, `requestId` (UUID), `key` (DER SubjectPublicKeyInfo / base64url), and `proof`. The installation private key signs:

```text
PHOTEREST-REQUEST-V1
<app>
<requestId>
<key>
```

The signer verifies the proof and allowed app, then issues a certificate in a plain text file:

```text
base64url(payloadUtf8).base64url(authoritySignature)
```

The authority signs the ASCII bytes of the first segment, not the decoded JSON. Payload: `{ "v":1, "id":"<UUID>", "app":"<application ID>", "key":"<installation SPKI>", "engines":["google"] }`. Classic permits `google,baidu`; Alpha permits `google` only. No expiry is assigned. App checks the authority signature, its package, its own Keystore public key, allowed engines and local revocation marker before importing. A failed import must not replace a working certificate.

## HTTP requests

Supported routes are `GET /v1/quota`, `POST /v1/search/google`, and `POST /v1/search/baidu`. A POST body is the existing bounded JPEG (at most 2,500,000 bytes); `Content-Type` and actual `Content-Length` retain their existing contract. GET has an empty body. Query strings are rejected.

Headers:

- `Authorization: Photerest <certificate>`
- `X-Photerest-Time: <Unix time in milliseconds>`
- `X-Photerest-Nonce: <16 cryptographically random bytes / base64url>`
- `X-Photerest-Signature: <installation signature / base64url>`

The installation signs:

```text
PHOTEREST-SEARCH-V1
<METHOD>
<pathname>
<timestamp as sent>
<nonce as sent>
<base64url(SHA256(exact body bytes))>
<base64url(SHA256(certificate UTF-8 bytes))>
```

The authority key must be trusted from configuration, never from the request. Worker verifies the certificate, package/engine grants, revocation, ±300,000 ms time tolerance, installation key and signed content. The certificate header is limited to 8 KiB. Validated nonces are stored as `<license ID>:<nonce>` in the existing quota Durable Object and expire after ten minutes. Nonce registration and paid quota reservation use its existing synchronous transaction. GET also records a nonce but never reserves paid quota; its response contains only the engines authorized by that certificate.

A duplicate request returns 401 and never calls a provider. Authorization errors use `{ "error":"unauthorized|authorization_revoked|authorization_time|authorization_replay" }` and HTTP 401. The Android client persists a revocation marker on a revoked reply. Existing quota, rate, timeout and upstream errors retain their prior meanings. No automatic provider retry is added.

## Deployment

`AUTH_PUBLIC_KEY` is the authority SPKI in base64url. `AUTH_REVOKED_IDS` is a JSON array of revoked certificate IDs. `SEARCH_AUTH_REQUIRED` defaults to strict in code; the deployed configuration is explicitly `"true"`. Only the rollout staging flag value `"false"` temporarily allows absent credentials; even in staging, supplied invalid credentials are rejected. Do not roll production back to anonymous mode or recreate the shared quota object.

Tests use ephemeral signing keys and mocked providers. Generated test licenses signed by the real local owner authority have been revoked in production. Neither provider keys nor owner private keys belong in the APK or repository.
