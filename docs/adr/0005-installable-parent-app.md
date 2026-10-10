# 5. The parent and student app is an installable web app that caches only its static shell

Status: accepted (Phase 1, parent and student app)

## Context
Parents and students mostly use phones, often low-end Android phones on patchy mobile data, and the plan
asks for an "installable web app" rather than store apps. The same Next.js app serves staff, so there is
one code base and one sign-in. The app shows children's personal data (attendance, fees, homework, leave
reasons that may be medical), and phones are shared within families. There is no AWS, no push provider and no
budget for a native app in the pilot, and no new npm dependencies may be added.

## Decision
- **Manifest.** `src/app/manifest.ts` (served at `/manifest.webmanifest`): name "Akshara School Cloud",
  short name "Akshara", `start_url` `/app/dashboard`, `scope` `/`, `display: standalone`, the page
  background `#f5f6f3` as theme and background colour, and icons in `public/icons` (192 and 512 px PNG, a
  512 px maskable PNG with the mark inside the safe zone, and an SVG). `src/app/apple-icon.png` and
  `appleWebApp` in the root layout cover iOS. The icons are the brand mark (अ on the amber tile); no
  school's logo or name is baked in, because one app serves every school.
- **Service worker.** One hand-written file, `public/sw.js`, no Workbox or other library:
  - It **caches only the static shell**: content-hashed scripts and styles under `/_next/static/`, the
    icons, the manifest and `/offline.html`, cache-first, in a cache named `akshara-shell-v1` (trimmed to
    200 entries; old `akshara-*` caches are deleted when a new version activates).
  - It **never caches** API responses (`/api/*` is not even intercepted), pages (navigations go to the
    network every time), file downloads, or anything from another origin. Personal data therefore never
    lands in the Cache Storage of a shared phone, and signing out leaves nothing behind but the shell.
  - When a page cannot be reached, the worker answers with `/offline.html` (English and Hindi, light and
    dark, no data), which offers to try again.
  - It is registered from the root layout by `ServiceWorkerRegistration`, **in production builds only**
    and only in a secure context (HTTPS or localhost); in development it would serve stale bundles.
    `/sw.js` is served with `Cache-Control: no-cache, no-store, must-revalidate` and registered with
    `updateViaCache: "none"`, so a new version reaches every phone on its next visit.
  - The existing Content-Security-Policy (`default-src 'self'`) already allows the same-origin worker and
    manifest; no header was loosened.
- **Install hint.** On phone widths, when the app is not already running installed, the home page shows
  a short tip on how to add it to the home screen, with an "Install app" button when the browser offers
  its own prompt (`beforeinstallprompt`). Hiding it is remembered on the device (`localStorage`).
- **No push, no background sync, no external calls.** The app works the same without the worker.

## Web push: what it would need (not built)
Notifying parents on their phone (an absence, a leave decision, a new circular) through web push needs:
1. **VAPID keys**: a key pair for the application server. The private key is a secret held in Secrets
   Manager (never in the repo or the browser); the public key is given to the browser when subscribing.
2. **A push sender**: the backend signs and sends each message to the browser vendor's push service
   (FCM for Chrome on Android, Mozilla autopush, Apple's push service for installed iOS web apps) with a
   web-push library or a hosted provider. This is an external network call, so it belongs behind the
   notifications outbox (`NotificationQueue`) as one more channel next to SMS, WhatsApp and email, with
   the same simulated sender in development and tests.
3. **Subscriptions per sign-in**: a tenant-owned table (`tenant_id`, user id, endpoint, `p256dh` and
   `auth` keys, user agent, created and last-used times) with row-level security, removed on sign-out,
   when the push service answers 404/410, and when a guardian's sign-in is unlinked.
4. **Consent**: the browser's permission prompt shown only after the parent asks for notifications
   (never on page load), a setting to switch them off per device, and a privacy notice entry. Messages
   should carry as little as possible ("New update from Akshara School") and open the app for details,
   because lock screens are visible to anyone holding the phone.
5. **The worker**: `push` and `notificationclick` handlers in `sw.js` that show the notification and open
   the right page, with no caching of the content.
6. **Producers**: the notifications slice listening to events such as `ChildLeaveRequested` (to the class
   teacher) and `ChildLeaveDecided` (to the parent), and to absence alerts.

## Consequences
- Parents can add the app to the home screen and open it like any other app; staff get the same
  benefit without anything new to maintain.
- Offline, the app shows a clear offline page instead of the browser's error, but no data: nothing about a
  child is readable without a connection and a valid sign-in. Offline reading of data would need
  encrypted, per-user storage cleared on sign-out, and is not planned.
- Changing the manifest or icons means bumping `VERSION` in `sw.js`, so phones fetch the new files.
- Web push is a separate, later decision that needs the secrets, the provider and the consent flow above.
