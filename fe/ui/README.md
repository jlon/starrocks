# StarRocks Web Console

React + Vite SPA for the StarRocks FE/BE diagnostics console. It replaces the
legacy server-rendered HTML pages and talks to the new `/api/web/v1/*` JSON API.

## Development

```bash
pnpm install
pnpm exec vite build   # regenerate src/routeTree.gen.ts after adding a route
pnpm build             # type-check + production build into dist/
pnpm dev               # Vite dev server; proxy /api to a running FE
```

The production bundle is committed under `dist/`; `build.sh` copies it to
`output/fe/webroot/ui/`. Do not add Node to the release image.

## Layout

- `src/routes/` — TanStack Router file routes; `_authenticated` guards the console.
- `src/features/` — one folder per page (`overview`, `backends`, `queries`, …).
- `src/components/` — layout, shadcn/ui primitives, data-table helpers.
- `src/lib/api.ts` — same-origin fetch wrapper and 401 handling.

## Authentication

The SPA posts credentials to `/api/web/v1/login`, which creates the existing
`STARROCKS_SESSION_ID` cookie. The cookie is `HttpOnly`, `SameSite=Lax`, and
expires after 24 hours. Logout invalidates the server-side session.

## Attribution

Based on [shadcn-admin](https://github.com/satnaing/shadcn-admin) (MIT).
