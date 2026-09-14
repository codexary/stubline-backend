English | [简体中文](0013-protect-transport-and-buyer-identity.zh-CN.md)

# ADR 0013: Protect transport and buyer identity

## Status

Draft for Stubline launch review.

## Context

Inventory service discovery lets `ticketing-api` find `inventory-service`; DNS alone does not authenticate it. The public API carries buyer requests and returns ticket download URLs. A buyer must not retrieve another buyer's ticket. The development issuer can issue a token for a caller supplied `sub`, so it is a local test fixture, not a production identity provider.

## Decision

Use HTTPS for the public API, require client certificates on inventory gRPC mTLS, validate buyer token signatures, time, issuer, and audience, and check order ownership by `sub` before payment and ticket retrieval.

Use HTTPS for the public JDK API, with a server certificate whose SAN matches the requested name. Use mTLS for the inventory gRPC channel: the API validates the inventory server against the internal CA, and inventory requires a client certificate from that CA. Keep the public and internal development CAs separate.

Require a buyer bearer token on order creation, payment, and ticket retrieval. Pin RS256; select a public key by `kid` from the issuer JWKS over validated HTTPS; verify signature, `exp`, `nbf` with 30 seconds of clock skew, `iss`, `aud=stubline-api`, and nonempty `sub`. Store `sub` with each order. Check ownership before calling the payment provider and again before issuing a ticket download URL. A protected payment request fails with 503 if no ownership store is configured. Use an RFC 6750 challenge for missing or invalid tokens.

Rotate signing keys by giving new tokens a new `kid` while retaining retired public keys for the 300 second maximum token lifetime. Replace the unauthenticated development issuer before public launch. The independent Keycloak spike evaluates authorization code with S256 PKCE for buyers and client credentials for services; its Spring Security resource server still requires explicit audience and authorization rules.

## Alternatives considered

- Plain HTTP and plaintext gRPC are simpler to run, but cannot protect traffic or authenticate the other endpoint on an untrusted network.
- Server TLS alone protects the API-to-inventory channel from a fake server but does not identify the calling service to inventory.
- Trusting a signed token without issuer, audience, time, or ownership checks permits cross-service reuse, stale credentials, payment for another buyer's order, or another buyer's ticket access.
- Kerberos offers KDC-issued service tickets and authenticators, but assumes a different key distribution and client environment from this public browser and OAuth2 setup.

## Consequences

Certificate issuance, private key storage, renewal, trust distribution, and failed-handshake diagnosis become operating work. `dev-ca.sh` sets mounted private keys and keystores to mode `600` and service UID 10001; production still needs separate key management. A stolen bearer token can be replayed until expiry, so TLS and token handling remain necessary. Development certificates and the unauthenticated issuer are limited to local use. The transport-security implementation mTLS, JWT, and payment ownership tests cover acceptance and rejection; the Keycloak spike's Docker tests cover role and audience mapping. The product remains on the development issuer at the transport-security implementation.
