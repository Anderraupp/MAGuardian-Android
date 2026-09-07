# Antivírus M&A

Web-based antivirus dashboard for monitoring, detecting, and removing malicious popup/adware apps from devices.

## Collaboration Workflow

- After completing and validating project changes, send them to the connected GitHub repository so they are available via `git pull`.

## Architecture

- **Frontend**: React + TypeScript with Vite, TanStack Query, Wouter routing, Shadcn UI, Framer Motion
- **Backend**: Express.js REST API
- **Database**: PostgreSQL with Drizzle ORM
- **Styling**: Tailwind CSS with green security theme, dark mode support

## Pages

- `/` - Dashboard: Protection status, stats (threats found/removed, last scan), recent threats, scan activity
- `/threats` - Threats list: All detected threats with details dialog
- `/history` - Scan history: Complete log of all scans performed (no app count)
- `/settings` - Settings: Protection toggles, permissions, theme, about

## API Endpoints

- `GET /api/protection-status` - Current protection status
- `POST /api/protection-status/toggle` - Toggle protection on/off
- `GET /api/threats` - List all detected threats
- `DELETE /api/threats/:id` - Remove threat record
- `GET /api/scan-history` - List all scan history
- `POST /api/scan` - Run a new scan (simulated, no app counting)

## Database Tables

- `threats` - Detected malicious apps
- `protection_status` - Current protection state and stats
- `scan_history` - Scan operation logs
- `installed_apps` - Legacy table (not used in UI)
- `users` - User accounts (unused in current MVP)

## Design Decisions

- No app counting: Scans run without counting/listing installed apps
- Simulated threats from hardcoded malware app pool
- Navigation: Painel, Ameaças, Histórico, Configurações (no Apps page)

## PWA Support

- manifest.json with app name, icons, and standalone display mode
- Service worker (sw.js) with cache-first strategy for offline support
- Install prompt component for adding to home screen
- Apple touch icon and theme color meta tags

## Key Features

- Real-time protection status monitoring
- Simulated device scanning with threat detection
- Threat severity classification (high/medium/low)
- Scan history tracking
- Dark/light mode toggle
- Responsive sidebar navigation
- Portuguese (Brazilian) language UI
