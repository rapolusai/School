# Phase 3 — Languages (English, Hindi, Telugu)

## Endpoints

| Method | Path | Who | Body / result |
| --- | --- | --- | --- |
| GET | `/api/me/preferences` | any signed-in school user | `{"language": "en" \| "hi" \| "te" \| null}` (null = never chosen) |
| PUT | `/api/me/preferences` | any signed-in school user | `{"language": "te"}` → same shape; 400 with `errors.language` for anything else |

- Stored in `preferences.user_language` (migration `V27__languages.sql`), one row per account, tenant RLS.
- Each change writes the audit event `user_language.updated` (entity `user_language`, details `from`/`to`).
- `PUT /api/notifications/settings` now also accepts `alertLanguage: "te"` as the school default.
- Messages to a guardian use that guardian's own preference (matched by phone or email to their
  account) and otherwise the school default.
- The web app applies the account language on sign-in, unless a language was already picked on that
  device (stored under `akshara.lang`); picking a language in the switcher also saves it to the account.
- Dates and numbers use `Intl` with `en-IN`, `hi-IN` or `te-IN` (Indian digit grouping: 12,34,567).

## Adding a fourth language (e.g. Tamil, `ta`)

1. Copy `frontend/src/lib/i18n/en.ts` to `ta.ts` and translate every value; keep keys, order and `{placeholders}`.
2. In `frontend/src/lib/i18n/index.ts` import it, add `{ code: "ta", label: "தமிழ்", htmlLang: "ta" }` to `LANGUAGES`,
   add it to `dictionaries`, and return `ta-IN` from `localeFor`.
3. Accept `l==="ta"` in `frontend/src/lib/theme-script.ts` so the first paint uses it.
4. Add `ta` to the `describe.each` in `frontend/src/lib/i18n/i18n.test.ts` (parity is then enforced).
5. Add `"ta"` to `AlertLanguage` in `frontend/src/lib/types/notifications.ts` and the option in attendance settings.
6. Backend: add `TAMIL` to `MessageTemplates.LANGUAGES` with a text for every template (the test checks placeholders).
7. Extend the language switches in `CalendarReminders` and the README in `privacy/ExportPackager`.
8. Widen the `^(en|hi|te)$` patterns in `PreferencesController`, `PreferencesService`, `NotificationSettings*`.
9. New migration: widen `ck_settings_language` and the `user_language` language check to include `'ta'`.
10. Run `mvn -q verify` and `npm run lint && npm run typecheck && npm test`; check a page at 390 px.
