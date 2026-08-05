English | [简体中文](0011-store-etickets-as-objects.zh-CN.md)

# ADR 0011: Store e-tickets as objects

## Status

Accepted on 2026-08-06.

## Context

After payment, buyers need to download a PDF e-ticket from anywhere. The API should not stream the file. PostgreSQL already owns the order and can store its object key. A colleague proposed a shared NFS mount. The NFS cache experiment showed that two clients can observe different file contents until an attribute check or reopen, depending on mount options. A database byte column would put ticket bytes on the transactional storage and its backup path.

## Decision

Render the PDF in `ticketing-api`, upload it through the S3 API to bucket `stubline-tickets` at `tickets/<order-id>.pdf`, then record that key on the order under a row lock and conditional update. A concurrent issuer waits and skips the upload after it sees the recorded key. `GET /orders/{id}/ticket` looks up the key and returns a five-minute presigned GET URL. The client downloads from object storage. Use SeaweedFS's S3 gateway in local Compose; configure endpoint override and path-style addressing there. Omit the override when using AWS S3. Treat each ticket key as immutable and use the recorded key for lookup, not a prefix listing.

## Alternatives considered

- PostgreSQL byte column: simpler transactional lookup, but ticket data enlarges the database and its backups and still leaves API streaming to solve.
- Shared NFS volume: preserves a file-path interface, but requires every API replica to mount the same export and accept file-cache freshness and write-policy behaviour. The ticket path needs whole-object upload and download, not shared open-file editing.
- API streaming from local disk: binds a buyer's download to one API instance and puts file transfer on API capacity.

## Consequences

The URL is a bearer capability for one object until expiry. It must be protected in logs and must use a public endpoint whose host matches the signed request. The S3 service must be reachable separately from the API. Upload and database key update are not one transaction; a failed update may leave an unreferenced object, and a failed upload after payment needs recovery. Concurrent issuance is serialized by the order row lock, so a second issuer skips the upload once the key has been recorded. A retry after an upload succeeded but before the database commit can PUT the same deterministic key again. The current implementation has no buyer authorization on the ticket route; authentication and authorization must be added before exposing it to untrusted buyers.
