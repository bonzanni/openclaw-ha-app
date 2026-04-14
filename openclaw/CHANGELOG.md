# Changelog

## 0.3.16

- Register Supervisor discovery service for companion integration
- Enables zero-config setup of the openclaw HA integration

## 0.1.0

- Initial release
- S6 v3 service management (openclaw-init, openclaw-gateway, nginx, ttyd)
- Full ingress support with WebSocket — Control UI works in HA sidebar
- Auto-connect via setup page (one-time gateway URL confirmation)
- nginx reverse proxy with server-side token injection
- Auto-generated gateway token (never exposed to the browser)
- Optional web terminal (ttyd) at /terminal/
- AppArmor security profile with S6 v3 paths
- Watchdog health monitoring via /healthz
- Cold backup support
- OpenClaw 2026.4.2
