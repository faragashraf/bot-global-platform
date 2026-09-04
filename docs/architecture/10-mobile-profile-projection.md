# Mobile Profile Projection

## Direction of data flow

The mobile profile integration is strictly one-way:

```text
Connect V2 -> publish snapshot -> Bot Global -> authenticated mobile read
```

ENPO Mobile never calls Connect V2 for profile data. Bot Global never calls
Connect V2, Oracle, or another upstream profile service while handling a mobile
profile request. The read path depends only on the stored Pairing projection.

## Authoritative identity

The machine-authenticated application creates a pairing challenge with its
authenticated external subject. Pairing stores that subject on `MobileDevice`
together with the server-derived platform client id. A valid device credential
restores the same application and subject claims. Revoked credentials do not
authenticate.

The profile key is therefore:

```text
(PlatformClientId, ExternalSubjectId)
```

Neither endpoint accepts a client-selected application id. The mobile read
endpoint accepts no target user or subject parameter.

## Publish contract

`PUT /api/mobile-profile-snapshots` requires platform machine authentication
and the `profiles:publish` capability. The request contains:

- `externalSubjectId`
- `displayName`
- optional `jobTitle`
- optional `organizationUnit`
- positive monotonic `version`
- `publishedAtUtc`

The publisher can write only inside its authenticated application scope, and
the target must already have an active pairing in that scope. Repeating an
identical version and payload is idempotent. A lower version is ignored. Reusing
a version with different content is rejected as a conflict.

Connect V2 remains the source of truth. Its later producer work must map the
authenticated Connect user id to `externalSubjectId`, publish the minimized
snapshot through the existing machine-authenticated Public Platform client,
persist or derive a monotonic version, retry transient failures idempotently,
and publish again whenever an approved field changes. The platform client must
be granted `profiles:publish` before that producer is enabled.

## Mobile read contract

`GET /api/mobile/profile` requires the mobile device authentication scheme. It
derives the platform client and external subject from the validated credential,
then reads only the stored projection. A missing snapshot returns
`profile_not_available_yet`; there is no upstream fallback.

The response exposes only:

- `displayName`
- optional `jobTitle`
- optional `organizationUnit`
- `version`
- `updatedAtUtc`

National identifiers, personal contact details, provider subjects, employee
records, authorization claims, credentials, and tokens are excluded.

## Persistence and cache

`pairing.MobileProfileSnapshots` owns one row per application/subject pair and
uses a row version for optimistic concurrency. Migration
`AddMobileProfileSnapshots` must be reviewed and applied through the normal
backend deployment process; it is not applied by mobile builds or tests.

ENPO uses a network-only profile policy. Display-safe profile fields remain in
memory only while the paired application process is active. The state is
invalidated when pairing is unavailable, so revoked or unpaired devices cannot
continue presenting a locally cached profile as current.
