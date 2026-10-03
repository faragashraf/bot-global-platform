# Bot Global Platform Content Map

Date: 2026-10-03

This is a working planning document for turning the current Bot Global Platform
work into a clear public story, product catalog, and runtime showcase. It is
based on repository evidence and the current hosted behavior. It is not a
marketing final, and it should not invent product claims that are not backed by
the running system or approved release material.

## Runtime Reality

- The public site is live at `https://www.botglobalservice.com`.
- The admin sign-in flow is working after the hosted session cookie fix.
- The public catalog engine is live and currently exposes one published product:
  SentriCam.
- The current SentriCam public listing is still placeholder-level and needs
  real content, screenshots, links, and release positioning.
- `/apps`, `/games`, and `/programs` exist as catalog category surfaces.
- `/games` and `/programs` are structurally ready, but currently have no strong
  public product entries.
- `/portfolio` is now reachable on IIS and works as a professional case-study
  surface. It should stay separate from the product catalog.
- The Admin Studio can load the catalog and author draft content. Published
  product lifecycle controls still need improvement, especially archive,
  publish/unpublish, delete where appropriate, and media management.

## Product And Showcase Candidates

| Candidate | What it is | Strong story | Current readiness | Content needs |
| --- | --- | --- | --- | --- |
| Bot Global Platform | The shared web, backend, admin, identity, catalog, notifications, pairing, and mobile foundation. | A bilingual, theme-aware platform that turns separate products into a managed portfolio. | Strong as a platform story. | Clear homepage narrative, platform capability section, runtime screenshots, admin screenshots. |
| SentriCam | Local-first camera monitoring product with Android devices, a Hub, browser dashboard, pairing, local archive, live view, and camera controls. | Private camera monitoring without making the cloud the center of the product. | Good source material exists. Public listing is currently too thin. | Real screenshots, approved wording, setup journey, privacy boundary, support links. |
| Lamma / Family Games XO | Online XO game built on the Family Games mobile platform with guest play, QR/native invitations, recovery, bilingual UX, and server-authoritative gameplay. | A social game foundation, starting with XO, designed for invites and reliable multiplayer. | Good product and architecture evidence. Store release assets are not complete. | Final screenshots, Play listing copy, icon/feature graphic, support/privacy URLs, voice limitations wording. |
| NQRB / Noqrb | Voice calling app with Google sign-in, private circle, invites, guest call links, history, ringtones, blocking, settings, and account deletion surfaces. | A calm, human voice product focused on trusted connections and simple calling. | Strong app surface exists, but compliance and release decisions still need closure. | Privacy/deletion verification, TURN/logging decisions, Play data safety closure, final release positioning. |
| ENPO Connect Mobile | Secure companion app for Egypt Post Connect with QR pairing, notifications, inbox, profile projection, and device credential flow. | A controlled enterprise companion, not a loose public consumer app. | Strong internal/enterprise story. Public use depends on approval and confidentiality. | Decide whether it is public, private, or anonymized as an enterprise case study. |
| Shared Mobile Platform | Kotlin Multiplatform foundation for identity, secure storage, localization, notifications, updates, invitations, pairing, realtime recovery, and voice contracts. | One mobile foundation powering multiple Bot Global apps. | Very strong capability story. | Show it as platform proof, not as a standalone consumer product. |
| Professional Portfolio | Case studies including Digital Egypt, prosecution integrations, background jobs, internal package services, reporting, certificates, and experience. | Proof of serious engineering history behind the product work. | Public-ready and now reachable. | Keep polished, but separate it from `/apps`, `/games`, and `/programs`. |

## Recommended Site Architecture

Keep these surfaces intentionally different:

- Home: Bot Global as a platform and product studio. Show the strongest public
  products and the platform capabilities behind them.
- Apps: consumer and enterprise applications such as SentriCam, NQRB, and ENPO
  Connect when approved.
- Games: Lamma / Family Games first, then future games under the same foundation.
- Programs: installable tools, desktop utilities, hubs, or supporting software
  when they become public products.
- Portfolio: professional case studies and career proof. This should support
  trust, but it should not replace the product catalog.
- Admin Studio: internal operational surface for catalog, notifications, device
  pairing, platform clients, and future publishing workflows.

## Product Detail Template

Every product detail page should answer the same questions:

1. Product promise: one direct sentence that explains what the product does.
2. Runtime status: live, internal testing, beta, coming soon, or enterprise-only.
3. Who it is for: the user, team, family, company, or institution.
4. Core workflows: the real actions someone can perform today.
5. Screenshots and media: runtime images, mobile screens, admin screens, or setup
   journey visuals.
6. Platform capabilities used: identity, notifications, pairing, realtime,
   admin, localization, privacy boundary, or mobile foundation.
7. Trust and support: privacy policy, account deletion, support contact, data
   handling notes, and release limitations.
8. Next milestone: what makes the product more complete or more public-ready.

## Immediate Content Backlog

1. Replace the SentriCam placeholder listing with real product content.
2. Add Lamma / Family Games as the first Games product with careful release
   status wording.
3. Add NQRB only after privacy, account deletion, TURN/logging, and Play data
   safety decisions are verified.
4. Decide whether ENPO Connect is public, private, or shown as an anonymized
   enterprise case study.
5. Add a platform capability section to the public site so the audience
   understands the shared foundation behind the products.
6. Improve Admin Catalog lifecycle controls: publish, archive/unarchive, and
   media management.
7. Add product-level support, privacy, and release status fields to the catalog
   model if the current model cannot express them cleanly.
8. Capture approved runtime screenshots for each public product.

## Decisions Needed Before Final Copy

- Should Bot Global primarily sell owned products, engineering services, or both?
  Recommended answer: both, but lead with owned products and use services/case
  studies as proof.
- Can SentriCam be publicly marketed now, and which screenshots are approved?
- Should Lamma be listed as internal testing, beta, or coming soon?
- Should NQRB appear publicly before all Google Play and privacy decisions close?
- Should ENPO Connect be visible publicly, or only described privately as an
  enterprise companion pattern?
- Which products need Arabic-first copy, English-first copy, or equal bilingual
  treatment?

## Content Governance Rules

- Do not describe a product as generally available unless deployment, support,
  and release status are confirmed.
- Do not publish privacy, compliance, or data handling claims without matching
  implementation and policy links.
- Do not mix portfolio case studies into the product catalog as if they were
  purchasable products.
- Do not show internal institution names or enterprise workflows publicly unless
  explicitly approved.
- Treat screenshots as release assets: review them for secrets, test data,
  internal IDs, tokens, emails, and customer information before publishing.

## Recommended First Execution Path

Start with SentriCam because it is already the only live published public catalog
entry. The first concrete improvement should be a real SentriCam product page:

- Short hero promise.
- Local-first privacy boundary.
- Setup journey: Hub install, folder/storage setup, QR pairing, device ready.
- Mobile camera role and browser dashboard role.
- Current limitations: LAN live view in V1, cloud and AI features deferred.
- Approved screenshots or simple product visuals.

After SentriCam, add Lamma as the first Games entry. Then revisit NQRB and ENPO
Connect after the release and visibility decisions are closed.
