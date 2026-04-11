# Design Review: OpenClaw HA Add-on (Replacement)

## Overall Assessment: NEEDS REVISION (Feasible with conditions)

The existing `techartdev/OpenClawHomeAssistant` add-on is functional but structurally poor. A ground-up rewrite following honcho-ha-app patterns is the right approach. Key decisions around ingress strategy and Node.js installation method need to be finalized before implementation.

---

## Part 1: Existing Implementation — Critical Drawbacks

### Summary Table

| Area | Status | Severity |
|---|---|---|
| No S6-overlay — 800+ line manual `run.sh` | Reimplements process supervisor in shell | **Critical** |
| No AppArmor profile | AI agent with shell exec + Chromium runs unconfined | **Critical** |
| `host_network: true` | Bypasses all Docker network isolation | **Critical** |
| ttyd has no authentication | Shell access to anyone with HA UI access | **Critical** |
| `homeassistant_token` schema type is `str?` not `password` | Token visible in UI/logs | **High** |
| No bashio usage | No log levels, no typed config, no HA API integration | **High** |
| Homebrew baked into Docker image | Adds GB of bloat, non-reproducible builds | **High** |
| No health checks | Supervisor cannot detect unhealthy state | **Medium** |
| No multi-stage build | Build tools remain in runtime image | **Medium** |
| Debug logging always-on | Log noise, potential secret leakage | **Medium** |
| OpenClaw version hardcoded in Dockerfile | Manual maintenance churn for every version bump | **Medium** |
| `build.yaml` instead of Dockerfile labels | Deprecated pattern | **Medium** |
| TLS cert validity 10 years | Rejected by modern browsers (>398 day limit) | **Medium** |
| `router_ssh_host` required in schema but optional in code | Validation mismatch | **Medium** |
| `vim`, `nano`, `sudo` in production image | Unnecessary attack surface | **Low** |

### Detailed Findings

**Process Management:** The add-on sets `init: false` (disabling S6) and uses `CMD ["/run.sh"]`. The 800+ line `run.sh` tracks nginx, ttyd, and the OpenClaw gateway via shell variables, `flock`, `kill -0` polling, and a three-tier PID detection strategy (port scan via `ss`, `pgrep`, `/proc` scan). This is exactly what S6-overlay was designed to solve. There are no finish scripts, no per-service health checks, and cleanup only runs on INT/TERM signals to the shell.

**Ingress:** Ingress is declared but only serves a landing page with buttons. The actual Gateway UI opens in a new browser tab to a direct `IP:port` URL, requiring `host_network: true`. The stated reason is "WebSocket apps have problems with HA ingress proxy." This claim is **outdated** — HA Supervisor's ingress has supported WebSocket upgrades since ~2019 (confirmed in Supervisor source code).

**Homebrew:** Installing Homebrew inside a Docker image is a serious anti-pattern. It bakes in a full Git clone of the Homebrew tap (hundreds of MB), a separate `linuxbrew` user, `sudo` dependency, and a startup-time rsync of the entire Homebrew directory to `/config/.linuxbrew`. The official OpenClaw Docker image does not use Homebrew.

**Security:** The token is stored as plaintext `str?`, ttyd provides unauthenticated shell access, `host_network: true` gives the container access to all host network interfaces, there's no AppArmor confinement, and `gateway_env_vars` uses a blocklist (not allowlist) for dangerous env vars like `LD_PRELOAD` and `NODE_OPTIONS`.

---

## Part 2: Reference Architecture (honcho-ha-app)

The honcho-ha-app is a well-structured HA add-on that demonstrates:

- **S6 v3 service graph** with proper `s6-rc.d/` directories, dependency ordering, oneshot init + longrun services
- **Multi-stage Dockerfile** (Python builder + HA Debian runtime)
- **Bashio integration** throughout with `#!/command/with-contenv bashio`
- **Deep health checks** (validates DB connectivity, not just process alive)
- **`watchdog: http://[HOST]:8000/health`** for Supervisor auto-recovery
- **AppArmor profile** with S6 v3 paths
- **`backup: cold`** for database-backed services
- **`sleep infinity` + exit 256** pattern for optional/disabled services
- **Single env file pattern** — init writes secrets to `/data/honcho/env`, services source it
- **`.gitattributes`** with `* text=auto eol=lf` (critical for Windows development)

---

## Part 3: OpenClaw Runtime Requirements

| Requirement | Details |
|---|---|
| **Runtime** | Node.js 24 (or 22.16+ LTS) |
| **Ports** | 18789 (Gateway WS+HTTP), 18790 (Bridge Protocol) |
| **Config** | `~/.openclaw/openclaw.json` (JSON5) + `.env` |
| **Persistence** | Workspaces, skills, sessions, extensions, media |
| **Health** | Built-in `/healthz` (liveness) + `/readyz` (readiness) |
| **Auth** | Token-based or trusted-proxy mode |
| **Optional** | Chromium (browser automation), ttyd (web terminal) |
| **User** | Non-root `node` (uid 1000) |

---

## Part 4: Proposed Architecture

### Directory Structure

```
openclaw-ha-app/
├── repository.yaml
├── README.md
├── CHANGELOG.md
├── .gitattributes                     # * text=auto eol=lf
├── .gitignore
└── openclaw/                          # Add-on directory
    ├── config.yaml
    ├── Dockerfile
    ├── apparmor.txt
    ├── icon.png / logo.png
    ├── DOCS.md
    ├── CHANGELOG.md
    ├── translations/
    │   └── en.yaml
    └── rootfs/
        └── etc/
            ├── nginx/
            │   └── servers/
            │       └── ingress.conf   # tempio-templated nginx config
            └── s6-overlay/
                ├── scripts/
                │   └── openclaw-init.sh
                └── s6-rc.d/
                    ├── user/
                    │   └── contents.d/
                    │       ├── openclaw-gateway
                    │       ├── nginx
                    │       └── ttyd
                    ├── openclaw-init/
                    │   ├── type           # oneshot
                    │   └── up             # /etc/s6-overlay/scripts/openclaw-init.sh
                    ├── openclaw-gateway/
                    │   ├── type           # longrun
                    │   ├── run
                    │   ├── finish
                    │   └── dependencies.d/
                    │       └── openclaw-init
                    ├── nginx/
                    │   ├── type           # longrun
                    │   ├── run
                    │   ├── finish
                    │   └── dependencies.d/
                    │       └── openclaw-init
                    └── ttyd/
                        ├── type           # longrun
                        ├── run            # sleep infinity when disabled
                        ├── finish         # exit 256 when disabled
                        └── dependencies.d/
                            └── openclaw-init
```

### S6 Dependency Graph

```
base ──► openclaw-init (oneshot) ──┬──► openclaw-gateway (longrun)
                                   ├──► nginx (longrun)
                                   └──► ttyd (longrun, conditional)
```

### Dockerfile Strategy (Multi-Stage)

```
Stage 1 (openclaw-build): node:24-bookworm-slim
  ├── npm install -g openclaw@<pinned-version> mcporter
  └── Result: /usr/local/lib/node_modules/{openclaw,mcporter} + /usr/local/bin/node

Stage 2 (runtime): ghcr.io/home-assistant/{arch}-base-debian:bookworm
  ├── COPY --from=openclaw-build /usr/local/bin/node /usr/local/bin/
  ├── COPY --from=openclaw-build /usr/local/lib/node_modules /usr/local/lib/node_modules
  ├── RUN ln -s ../lib/node_modules/openclaw/... /usr/local/bin/openclaw
  ├── apt-get install: nginx, curl, ttyd, openssl, procps
  ├── Optional: Chromium via Playwright (build ARG)
  ├── COPY rootfs /
  ├── RUN chmod +x ... (Windows safety)
  └── No CMD (S6 handles init)
```

**Key decision — npm global install vs source build:** Using `npm install -g openclaw@<version>` is dramatically simpler and faster than building from source (no Bun, no pnpm, no TypeScript compilation). The trade-off is the npm package may lag GitHub by a few days. **Recommendation: start with npm install, switch to source build only if needed.**

### Ingress Strategy

**HA Supervisor ingress DOES support WebSocket.** The claim in the old add-on is outdated.

**Recommended approach: nginx ingress → token auth with pre-injection**

1. nginx listens on the ingress port (tempio-templated `{{ .interface }}:{{ .port }}`)
2. Proxies all HTTP + WS upgrade traffic to `127.0.0.1:18789`
3. Injects `Authorization: Bearer <token>` header server-side (token never reaches browser)
4. OpenClaw configured with `auth.mode: "token"` + `trustedProxies: ["127.0.0.1"]`

This eliminates `host_network: true` entirely. Users who need direct access can enable port mappings (disabled by default: `18789/tcp: null`).

**Risk: Control UI WS URL auto-detection.** If OpenClaw's Control UI uses `window.location` to derive the WS URL, it will work through ingress. If it hardcodes `ws://127.0.0.1:18789`, it will fail. This needs empirical testing. Fallback: serve a landing page on ingress (like the old add-on) and expose port 18789 directly.

### Configuration Flow

```
HA Options (config.yaml)
    │
    ▼
openclaw-init.sh (oneshot)
    ├── Reads options via bashio::config
    ├── Queries HA URL for allowedOrigins
    ├── Writes /data/openclaw/env (internal secrets, mode 600)
    ├── Writes /config/.openclaw/openclaw.json (first boot only)
    └── On subsequent boots: patches only HA-managed keys via jq
    │
    ▼
openclaw-gateway run script
    ├── source /data/openclaw/env
    └── exec openclaw gateway run
```

**Critical lifecycle rule:** After first boot, users configure OpenClaw via the Control UI. The init script must NOT overwrite `openclaw.json` on every boot — only patch `gateway.controlUi.allowedOrigins` and `gateway.auth` fields.

### Proposed config.yaml Schema

```yaml
name: "OpenClaw"
slug: "openclaw"
version: "1.0.0"
description: "Local-first personal AI gateway connecting 24+ messaging platforms"
arch: [amd64, aarch64]
startup: application
boot: auto
init: false
ingress: true
ingress_port: 8099
ingress_entry: /
panel_icon: mdi:robot
panel_title: "OpenClaw"
watchdog: "http://[HOST]:18789/healthz"
backup: cold
apparmor: true
hassio_api: true
homeassistant_api: true
map: [addon_config:rw, data:rw]
ports:
  18789/tcp: null
  18790/tcp: null
ports_description:
  18789/tcp: "Gateway (direct access, not needed with ingress)"
  18790/tcp: "Bridge Protocol (multi-gateway federation)"

options:
  log_level: info
  gateway_port: 18789
  enable_terminal: false
  enable_ha_mcp: true

schema:
  log_level: list(trace|debug|info|notice|warning|error|fatal)
  gateway_port: port
  enable_terminal: bool
  enable_ha_mcp: bool
```

### Persistence Strategy

Two mount points serve different purposes:

```
/config/                              # Inside add-on container (addon_config mount)
├── .openclaw/                        # = host: <hassio>/addon_configs/<slug>/.openclaw/
│   ├── openclaw.json                 # = HA Core: /addon_configs/<hash>_openclaw/.openclaw/openclaw.json
│   ├── .env                          # API keys (user-managed)
│   ├── agents/                       # Agent dirs (agentDir config)
│   └── extensions/                   # User-installed extensions
├── clawd/                            # Agent workspaces (top-level, user-managed git repos)
├── clawd-butler/
├── clawd-builder/
├── clawd-finance/
└── ...

/data/openclaw/                       # Internal (not user-visible)
├── env                               # Generated env file (mode 600, SUPERVISOR_TOKEN etc.)
├── gateway_token                     # Auto-generated gateway token
├── sessions/                         # Session state
├── cache/                            # Media cache, temp files
└── logs/                             # Rolling logs
```

**Path mapping (critical for integration compatibility):**
- Add-on writes to `/config/` → Host: `<hassio>/addon_configs/openclaw/`
- HA Core reads: `/addon_configs/<hash>_openclaw/` — integration discovers `openclaw.json` here
- This matches the existing add-on layout exactly (`O:/17e0cc66_openclaw_assistant/.openclaw/`)

**Env vars:** `OPENCLAW_STATE_DIR=/config/.openclaw` and `OPENCLAW_CONFIG_PATH=/config/.openclaw/openclaw.json` override the default `~/.openclaw/` path.

### Security Posture

| Concern | Solution |
|---|---|
| `host_network: true` | Eliminated via ingress + port mappings |
| No AppArmor | Full profile with S6 v3 paths, `/proc` denials, raw network denial |
| Unmasked secrets | Gateway token auto-generated, never in HA options UI |
| ttyd unauthenticated | Protected by HA ingress auth; only runs when `enable_terminal: true` |
| Homebrew bloat | Removed entirely |
| `sudo` in container | Not installed |
| Docker socket access | Blocked in AppArmor (`deny /var/run/docker.sock rw`) |
| Env var injection | Blocklist for `LD_PRELOAD`, `NODE_OPTIONS`, `NODE_PATH`, etc. |
| Chromium sandbox | `--no-sandbox` with AppArmor compensation; `/dev/shm` tmpfs |

### Health Monitoring

- **Watchdog:** `http://[HOST]:18789/healthz` — Supervisor polls, auto-restarts on failure
- **Gateway finish script:** On crash (exit != 0 && != 256), calls `/run/s6/basedir/bin/halt` to trigger full restart
- **ttyd:** No watchdog — optional service, failure should not bring down the add-on

---

## Part 5: Risks and Open Questions

### Risk 1: Control UI WebSocket through Ingress (Medium)
The Control UI may hardcode the WS URL instead of deriving from `window.location`. Needs empirical testing. Mitigation: fall back to landing page + direct port access.

### Risk 2: OpenClaw Config Validation (Medium)
OpenClaw rejects configs with unknown keys or invalid types — the gateway refuses to start. The init script must generate a perfectly valid JSON5 config. Test with `openclaw doctor`.

### Risk 3: `trusted-proxy` Security Audit Warning (Low)
`openclaw security audit` emits a CRITICAL finding for trusted-proxy mode. This is expected and correct in the HA context. Document in DOCS.md.

### Risk 4: Build Size (Low)
Estimated 800MB-1.2GB image. Unavoidable for a full AI gateway with Node.js. Multi-stage build keeps it as lean as possible.

### Risk 5: armv7 Not Supported (Low)
OpenClaw's build requires Node 24 with native modules. armv7 (RPi 3) is not viable. Only amd64 + aarch64.

---

## Part 6: Implementation Plan

### Phase 1: Scaffold
1. Create repository structure (repository.yaml, openclaw/ dir)
2. `.gitattributes` with LF enforcement
3. Skeleton `config.yaml` with schema
4. Empty `rootfs/` tree with S6 service directories
5. Basic `apparmor.txt`

### Phase 2: Dockerfile
1. Multi-stage build (node:24-bookworm-slim → HA base-debian:bookworm)
2. `npm install -g openclaw@<version>` in builder stage
3. Copy Node.js + OpenClaw to runtime stage
4. Install nginx, ttyd, procps, openssl
5. `COPY rootfs /` + `chmod +x` pass
6. Test build for amd64

### Phase 3: S6 Services
1. `openclaw-init.sh` — config generation, env file, first-boot logic
2. `openclaw-gateway` run/finish scripts
3. `nginx` run/finish with ingress config
4. `ttyd` run/finish with conditional enable
5. Test service ordering and dependency graph

### Phase 4: Ingress + Auth
1. nginx config template with `{{ .port }}`/`{{ .interface }}`
2. WS proxy to 127.0.0.1:18789
3. trusted-proxy auth configuration
4. Test WS through ingress empirically
5. Fallback plan if WS fails

### Phase 5: Polish
1. `translations/en.yaml`
2. `DOCS.md` with setup instructions
3. `CHANGELOG.md`
4. Icon and logo
5. AppArmor profile refinement
6. End-to-end testing on HA

---

---

## Part 7: Companion Integration Compatibility Analysis

### Overall: PARTIALLY COMPATIBLE (4 critical issues to resolve)

The existing integration at `techartdev/OpenClawHomeAssistantIntegration` (v0.1.62) will **NOT** work out of the box with the new add-on. Four critical incompatibilities exist, all solvable.

### Integration Architecture Summary

The integration (v0.1.62) communicates with OpenClaw via:
- **REST only** (no WebSocket): `POST /v1/chat/completions`, `GET /v1/models`, `POST /tools/invoke`
- **Auth**: Bearer token in `Authorization` header + `x-openclaw-agent-id` header
- **Auto-discovery**: Scans `/addon_configs/<hash>_openclaw_*/` filesystem for `.openclaw/openclaw.json`
- **Port detection**: Reads `gateway_port` from Supervisor addon options
- **Entities**: sensors, binary_sensor, conversation agent, buttons, select, events
- **Services**: `send_message`, `clear_history`, `invoke_tool`
- **Poll interval**: 30 seconds

### Critical Compatibility Issues

#### Issue 1: Config Path Discovery (RESOLVED)

**Problem**: Integration scans `/addon_configs/<hash>_<slug>/.openclaw/openclaw.json` to find the gateway token.

**Resolution**: Using `addon_config:rw` as the primary config mount, the main `openclaw.json` lives at `/config/.openclaw/openclaw.json` — exactly where the integration expects it. No workaround needed.

#### Issue 2: Network Connectivity (CRITICAL)

**Problem**: With `host_network: false`, the gateway at port 18789 is NOT reachable at `localhost` from HA Core. The integration currently constructs `http://localhost:<port>`. HA Core and add-on containers are on Docker's internal bridge network (`172.30.32.0/23`).

**Fix (integration side, unavoidable)**: The integration must use the add-on's internal Docker IP (obtainable via Supervisor API `GET /addons/openclaw/info` → `ip_address` field) instead of `localhost`. This is the **one fix that cannot be solved from the add-on side alone** without reverting to `host_network: true`.

**Workaround (add-on side, temporary)**: Expose port `18789/tcp: 18789` (not null) as a default mapping so the gateway is reachable at the host IP. Less clean but avoids integration changes.

#### Issue 3: Auth Mode Conflict (CRITICAL)

**Problem**: Our add-on design uses `trusted-proxy` auth mode. In this mode, OpenClaw **completely ignores Bearer tokens** — the auth code returns before reaching the token branch. The integration sends Bearer tokens. When the integration connects directly to port 18789 (bypassing nginx), it comes from a non-trusted IP → auth fails.

**Fix (add-on design change)**: Use `token` auth mode (not `trusted-proxy`) in `openclaw.json`. Configure nginx to add `X-Forwarded-User` for ingress, but let the gateway use token auth natively. Both ingress (via nginx proxy headers) and integration (via Bearer token) work:
```json5
{
  gateway: {
    auth: { mode: "token", token: "<generated-token>" },
    trustedProxies: ["127.0.0.1"]  // nginx still passes through
  }
}
```

#### Issue 4: OpenAI API Disabled by Default (CRITICAL)

**Problem**: OpenClaw's `/v1/chat/completions` endpoint is **disabled by default**. The integration requires it for conversation agent and `send_message` service.

**Fix (add-on init script)**: Enable it in `openclaw.json`:
```json5
{
  gateway: {
    http: {
      endpoints: {
        chatCompletions: { enabled: true }
      }
    }
  }
}
```

### Warning-Level Issues

| Issue | Fix |
|---|---|
| `gateway_port` option missing from add-on schema | Add `gateway_port: 18789` as a read-only option in config.yaml |

### Integration Quality Issues Found

The existing integration has several quality issues worth fixing in a future rework:

| Issue | Severity |
|---|---|
| Uses `hass.data[DOMAIN][entry_id]` instead of `entry.runtime_data` | Medium |
| Missing `config_entry=` on DataUpdateCoordinator constructor | Medium |
| `DeviceInfo` as raw dict instead of typed dataclass | Low |
| No `SensorStateClass` on numeric sensors (breaks statistics) | Medium |
| "Clear History" only clears HA-side, not gateway session | Medium |
| `_CARD_URL` version hardcoded (not read from manifest) | Low |
| Raises `OpenClawApiError` instead of `HomeAssistantError` from services | Low |
| Zero test coverage (no `tests/` directory) | High |
| Fallback `aiohttp.ClientSession()` created outside HA session pool | Medium |
| `_VOICE_REQUEST_HEADERS` duplicated in two files | Low |

### Revised Add-on Design Changes Required

Based on this analysis, the add-on design from Part 4 must be updated:

1. **`config.yaml` map**: `[addon_config:rw, data:rw]` — user-facing config in `addon_config`, internal state in `data`
2. **Auth mode**: `token` (was `trusted-proxy`) — nginx ingress still works via trustedProxies
3. **Init script**: Config at `/config/.openclaw/openclaw.json` (where integration expects it — no shim needed)
4. **Init script**: Must enable `chatCompletions` endpoint in openclaw.json
5. **Config schema**: Add `gateway_port: 18789` as a dummy option
6. **Ports**: Consider `18789/tcp: 18789` as default-enabled (not null) for integration compat

### Future Integration Rework Roadmap

If/when we rework the integration:

1. **Replace filesystem discovery with Supervisor API**: `GET /addons/openclaw/info` → get IP, port, token from addon options directly
2. **Add WebSocket transport**: Subscribe to agent/chat events for real-time entity updates (replace 30s polling)
3. **Adopt `entry.runtime_data`** pattern (HA 2024.5+)
4. **Pass `config_entry=entry`** to DataUpdateCoordinator
5. **Use typed `DeviceInfo`** dataclass
6. **Add `SensorStateClass.MEASUREMENT`** to numeric sensors
7. **Add `diagnostics.py`** for debugging
8. **Change `iot_class`** from `local_polling` to `local_push` when WS is added
9. **Add test suite** (the biggest quality gap)
10. **Fix "Clear History"** to also clear gateway-side session

---

---

## Part 8: Home Assistant MCP Integration

### Use mcporter (Requires Installation)

**mcporter** is an external CLI tool (not bundled with OpenClaw) that bridges stdio↔HTTP for MCP servers. It must be installed separately via `npm install -g mcporter`. The multi-agent plan (`openclaw-multi-agent-plan-v5.md`) documents this pattern for HA:

```
Agent (Tina) ──exec──► mcporter call HA.<tool> ──HTTP──► http://localhost:8123/api/mcp
```

In the add-on context, the URL changes to the Supervisor proxy:

```
Agent ──exec──► mcporter call HA.<tool> ──HTTP──► http://supervisor/core/api/mcp
                                                   (with SUPERVISOR_TOKEN auth)
```

**No custom MCP server code needed. No bridge. No extra dependencies.**

### HA's Built-in MCP Server

HA 2025.1+ includes the `mcp_server` integration:

| Aspect | Details |
|---|---|
| **Transport** | Streamable HTTP (`POST /api/mcp`) + SSE (`GET /mcp_server/sse`) |
| **Auth** | Long-lived access token or OAuth |
| **From add-on** | `POST http://supervisor/core/api/mcp` with `SUPERVISOR_TOKEN` |
| **Supervisor support** | Proxy explicitly forwards MCP headers (`Mcp-Session-Id`, `MCP-Protocol-Version`) and streams SSE |

### Available Tools (23 intent-based)

| Tool | Purpose |
|---|---|
| `HassTurnOn` / `HassTurnOff` | Control lights, switches, fans, media, locks |
| `HassLightSet` | Brightness, color, color temperature |
| `HassSetPosition` | Covers/blinds position |
| `HassClimateSetTemperature` / `HassClimateGetTemperature` | HVAC control |
| `HassMediaPlay` / `HassMediaPause` / `HassMediaNextTrack` / `HassMediaVolume` | Media control |
| `HassVacuumStart` / `HassVacuumReturnToBase` | Vacuum control |
| `GetDateTime` | Current date/time |
| `GetLiveContext` | Snapshot of all exposed entity states (HA 2026.4+) |
| `CalendarGetEvents` / `TodoGetItems` | Calendar and todo access |
| `ScriptTool` (per script) | Run exposed scripts |
| Scene and bulk light controls | Custom exposed scripts |

**The safety boundary is HA's entity exposure config** (Settings > Voice Assistants > Expose). Only entities exposed there are accessible through MCP. This is the correct security model — HA controls what the AI can touch.

### Why mcporter, Not a Custom MCP Bridge

| | mcporter | Custom ha-mcp-bridge | mcp-proxy (Python) |
|---|---|---|---|
| **Custom code** | 0 lines | ~300 lines Node.js | 0 lines |
| **Ships with OpenClaw** | No (must `npm install -g mcporter`) | No (must bundle) | No (must install Python) |
| **Tested with OpenClaw** | Yes (multi-agent plan v5) | No | No |
| **Daemon mode** | Yes (keeps connections warm) | No | No |
| **Tools** | HA's 23 intent tools | Full REST API | HA's 23 intent tools |
| **User setup** | Enable `mcp_server` integration | None | Enable `mcp_server` integration |
| **Maintenance** | HA maintains tools | We maintain tools | We maintain bridge |
| **Extra container size** | 0 MB | ~2 MB | ~150 MB (Python) |

The only advantage of a custom bridge is full REST API access (`call_service`, `list_entities`, `get_history`). But the multi-agent plan explicitly concludes the 23 intent tools are sufficient: *"OpenClaw's `exec` tool is never needed for HA interaction"* and *"The safety boundary is HA's entity exposure config, not OpenClaw's tool policy."*

If full REST API is ever needed, agents can fall back to `exec` + `curl http://supervisor/core/api/...` with `SUPERVISOR_TOKEN` — no MCP required.

### mcporter Configuration

The init script writes mcporter config so it knows how to reach HA's MCP from inside the container:

```bash
# In openclaw-init.sh:
# Configure mcporter to reach HA MCP via Supervisor proxy
mcporter config set HA \
  --url "http://supervisor/core/api/mcp" \
  --header "Authorization: Bearer ${SUPERVISOR_TOKEN}"
```

Or equivalently in `openclaw.json` (agent-level config for Tina/butler):
```json5
{
  // mcporter server config — mcporter handles HTTP transport natively
  // Agent calls: mcporter call HA.HassTurnOn area="Living Room" name="Ceiling Light"
  // Agent calls: mcporter call HA.GetLiveContext
}
```

### Init Script Changes

`openclaw-init.sh` must:

1. **Configure mcporter** with the HA MCP endpoint and `SUPERVISOR_TOKEN`
2. **Start mcporter daemon** if `enable_ha_mcp` is true (keeps HA MCP connection warm)
3. **On subsequent boots**, update the `SUPERVISOR_TOKEN` (it can change between restarts)
4. **Health check**: Attempt `mcporter call HA.GetDateTime` to verify connectivity; warn in logs if `mcp_server` integration is not enabled in HA

### S6 Integration

mcporter daemon can optionally run as a lightweight S6 `longrun` service:

```
rootfs/etc/s6-overlay/s6-rc.d/
├── mcporter/
│   ├── type              # longrun
│   ├── run               # mcporter daemon start (or sleep infinity if disabled)
│   ├── finish            # mcporter daemon stop (or exit 256 if disabled)
│   └── dependencies.d/
│       └── openclaw-init
```

This keeps the HA MCP connection warm between agent sessions, reducing tool call latency. When `enable_ha_mcp: false`, uses the `sleep infinity` + exit 256 pattern.

### User Setup Requirement

The user must **enable the `mcp_server` integration** in HA:
- Settings > Devices & Services > Add Integration > "Model Context Protocol Server"
- Select which LLM API to expose (default: `assist`)

The add-on cannot enable this automatically. We should:
1. Document it prominently in DOCS.md as a required setup step
2. Detect it in the init script — if `POST http://supervisor/core/api/mcp` returns 404, log a clear warning
3. Consider a sensor in the companion integration that shows MCP server status

### Security Model

- **HA controls access**: Only entities exposed via Voice Assistants config are accessible
- **`SUPERVISOR_TOKEN` scoped to add-on**: Cannot access other add-ons or Supervisor management
- **Agent prompt constraints**: Tina's `exec` is constrained by AGENTS.md to `mcporter` commands only
- **Confirmation required**: Locks, alarm, and safety-critical actions require user confirmation
- **No raw API exposure**: mcporter talks to HA's intent system, not raw REST — agents can't call arbitrary services

### Future: If 23 Tools Aren't Enough

If we discover the intent tools are too limiting:
1. **First**: Check if a community HACS integration like `ganhammar/hass-mcp-server` offers richer tools via the same `/api/mcp` endpoint (it does: `call_service`, `list_entities`, `get_history`, `render_template`)
2. **Then**: Consider building a custom MCP server only for the specific missing capabilities
3. **Last resort**: Direct REST API via `exec` + `curl` with `SUPERVISOR_TOKEN`

---

## Final Implementation Plan

### Phase 0: Design Decisions (this review)
- [x] Examine existing add-on drawbacks
- [x] Study honcho-ha-app reference
- [x] Analyze companion integration compatibility
- [x] Analyze HA MCP integration strategy → **mcporter** (external, must install)
- [x] Fact-check against OpenClaw source and HA Supervisor source
- [ ] **Decision needed**: Default port mapping (null vs 18789) for integration compat
- [ ] **Decision needed**: Rework integration now or later?

### Phase 1: Scaffold
1. Repository structure (repository.yaml, openclaw/ dir)
2. `.gitattributes` with `* text=auto eol=lf`
3. `config.yaml` with 4-option schema
4. Empty rootfs tree with S6 service directories (including mcporter, /app/www)
5. Basic `apparmor.txt`

### Phase 2: Dockerfile
1. Multi-stage build (`node:24-bookworm-slim` → HA `base-debian:bookworm`)
2. `npm install -g openclaw@<version> mcporter` in builder stage
3. Copy Node.js + OpenClaw + mcporter to runtime stage
4. `apt-get install`: nginx, ttyd, curl, openssl, procps, jq
5. `COPY rootfs /` + `chmod +x` pass (Windows line ending safety)
6. No CMD (S6 handles init from base image)

### Phase 3: S6 Services
1. `openclaw-init.sh` (oneshot) — auto-generate token, write `openclaw.json` (first boot only), write env file, render nginx config via `tempio` (Go `{{ .var }}` syntax), configure mcporter for HA MCP
2. `openclaw-gateway` (longrun) — `source /data/openclaw/env && exec openclaw gateway run`
3. `nginx` (longrun) — serves ingress landing page + proxies to gateway/ttyd
4. `ttyd` (longrun, conditional) — `sleep infinity` + exit 256 when disabled
5. `mcporter` (longrun, conditional) — daemon mode for HA MCP when enabled

### Phase 4: Ingress Web UI
1. Landing page (`/app/www/index.html`) with Gateway and Terminal tabs
2. nginx tempio template: proxy `/gateway/` → 127.0.0.1:18789 (Bearer token injected server-side), proxy `/terminal/` → ttyd
3. Token auth mode in `openclaw.json` with `trustedProxies: ["127.0.0.1"]`
4. **Ingress gate** (must pass before Phase 5): test WS through ingress — if Control UI WS fails, ship with landing page + optional direct port in compatibility mode

### Phase 5: Polish + Testing
1. End-to-end: companion integration → add-on → mcporter → HA MCP → entity control
2. Translations (`en.yaml`), `DOCS.md` (include HA MCP setup instructions), `CHANGELOG.md`
3. Icons (icon.png 128x128, logo.png 250x100)
4. AppArmor profile refinement
5. Verify integration discovers token at `/config/.openclaw/openclaw.json` → HA Core sees `/addon_configs/<hash>_openclaw/.openclaw/openclaw.json`

### Phase 6 (Future): Integration Rework
1. Supervisor API discovery (replace filesystem scanning)
2. WebSocket transport for real-time events
3. Modern HA patterns (runtime_data, typed DeviceInfo, SensorStateClass)
4. Test suite

---

---

## Part 9: Ingress Web UI (Tabbed Landing Page)

### Overview

The HA sidebar panel serves a **tabbed landing page** via nginx that embeds both the Gateway Control UI and a web terminal, with authentication pre-injected server-side:

```
HA Sidebar ──ingress──► nginx (port 8099)
                          ├── /                  → landing page (tabs)
                          ├── /gateway/          → reverse proxy to 127.0.0.1:18789
                          │                        (Authorization: Bearer <token> injected by nginx)
                          └── /terminal/         → reverse proxy to ttyd 127.0.0.1:7681
```

### Pre-Authentication via nginx

nginx injects the gateway token into every proxied request server-side. The browser never sees or stores the token:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen {{ .interface }}:{{ .port }};

    # Landing page
    location = / {
        root /app/www;
        index index.html;
    }
    location /static/ {
        root /app/www;
    }

    # Gateway UI — pre-authenticated
    location /gateway/ {
        proxy_pass http://127.0.0.1:18789/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Authorization "Bearer {{ .token }}";
        proxy_read_timeout 86400;  # keep WS alive
    }

    # Terminal — conditional (ttyd)
    location /terminal/ {
        proxy_pass http://127.0.0.1:7681/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 86400;
    }
}
```

`{{ .interface }}`, `{{ .port }}`, `{{ .token }}` are filled by `tempio` (HA's template engine) from environment variables written by the init script.

### Landing Page Design

A minimal HTML page at `/app/www/index.html` with two tabs:

```
┌──────────────────────────────────────────────────┐
│  [🏠 Gateway]  [>_ Terminal]                      │
├──────────────────────────────────────────────────┤
│                                                   │
│  <iframe src="./gateway/" style="100% height">   │
│                                                   │
└──────────────────────────────────────────────────┘
```

- **Gateway tab** (default): Full OpenClaw Control UI — chat, channels, agent config, canvas
- **Terminal tab**: ttyd shell (hidden when `enable_terminal: false`)
- Pure static HTML/CSS/JS — no build step, no framework
- Remembers active tab via `localStorage`
- Responsive — fills the sidebar panel completely
- Tab visibility controlled by a data attribute set during init

### Security Model

| Layer | What it protects |
|---|---|
| **HA ingress auth** | Only authenticated HA users can reach the panel at all |
| **nginx token injection** | Token stays server-side — never sent to the browser, never in JavaScript |
| **ttyd conditional** | Terminal only starts when `enable_terminal: true`; nginx returns 404 for `/terminal/` when disabled |
| **AppArmor** | Confines terminal shell access within the container |

### WebSocket Through Ingress (Risk 1 Resolution)

The Control UI connects via WebSocket. Through ingress, the URL becomes:
```
wss://<ha-host>/api/hassio_ingress/<ingress-token>/gateway/
```

This works if the Control UI derives the WS URL from `window.location` (common SPA pattern). nginx handles the `Upgrade: websocket` header and proxies the connection with the Bearer token injected.

The init script writes to `openclaw.json`:
```json5
{
  gateway: {
    controlUi: {
      basePath: "/",  // nginx strips /gateway/ prefix via proxy_pass trailing slash
      allowedOrigins: ["<ha-url>"]  // from bashio::info or Supervisor API
    }
  }
}
```

**If WS doesn't work through ingress** (Control UI hardcodes the URL): the landing page shows a "Open in new tab" button linking to `http://<host>:18789` as a fallback. This degrades gracefully.

### config.yaml Schema (already in Part 4)

`enable_terminal: bool` is already declared in the base schema. No additional options needed — ttyd is protected by HA ingress auth.

### rootfs Additions

```
rootfs/
├── app/
│   └── www/
│       ├── index.html            # Tabbed landing page
│       └── style.css             # Minimal styling (dark theme matching HA)
└── etc/
    └── nginx/
        └── servers/
            └── ingress.conf.gtpl  # tempio template ({{ .interface }}, {{ .port }}, {{ .token }})
```

### Init Script Additions

```bash
# Render nginx config from template using tempio (Go text/template syntax)
bashio::var.json \
    interface "$(bashio::addon.ip_address)" \
    port "^$(bashio::addon.ingress_port)" \
    token "${GATEWAY_TOKEN}" \
    | tempio \
        -template /etc/nginx/servers/ingress.conf.gtpl \
        -out /etc/nginx/servers/ingress.conf

# Write terminal visibility flag for the landing page
if bashio::config.true 'enable_terminal'; then
  echo '{"terminal_enabled": true}' > /app/www/config.json
else
  echo '{"terminal_enabled": false}' > /app/www/config.json
fi
```

### No New S6 Services

This uses the existing services:
- **nginx** (already in plan) — just gets a richer config template
- **ttyd** (already in plan) — conditional, `sleep infinity` + exit 256 when disabled
- Landing page is static HTML — no process to manage

---

---

## Part 10: Final Review — Fact-Check Results

Review performed against OpenClaw source (`../openclaw`, v2026.4.2) and HA Supervisor source (main branch).

### Critical Corrections Applied

| Error | Was | Fixed To |
|---|---|---|
| `addon_config` container path | `/addon_configs/openclaw/` | `/config/` (addon_config mounts to `/config` inside container) |
| tempio syntax | `%%variable%%` | `{{ .variable }}` (Go text/template) |
| mcporter bundling | "Ships with OpenClaw" | External tool, must `npm install -g mcporter` in Dockerfile |
| terminal_password option | In config schema | Removed (HA ingress auth is sufficient) |
| Auth mode in Part 4 | trusted-proxy | token mode with nginx header injection |

### Verified Claims

| Claim | Status | Source |
|---|---|---|
| `/healthz` and `/readyz` return JSON | Confirmed | `server-http.ts:125-272` |
| Token mode accepts Bearer tokens | Confirmed | `auth.ts:438-454` |
| Trusted-proxy ignores Bearer tokens | Confirmed | `auth.ts:381-399` (early return) |
| chatCompletions disabled by default | Confirmed | `server-runtime-config.ts:79` default `false` |
| `OPENCLAW_STATE_DIR` overrides config dir | Confirmed | `utils.ts:289-303` |
| `controlUi.basePath` and `allowedOrigins` exist | Confirmed | `types.gateway.ts:103,107` |
| `openclaw gateway run` foreground command | Confirmed | `docs/cli/gateway.md:33` |
| `init: false` lets S6 be PID 1 | Confirmed | Supervisor sets Docker `"Init": false` |
| `[HOST]` resolves to container bridge IP | Confirmed | Supervisor replaces with `self.ip_address` |
| `backup: cold` stops add-on before snapshot | Confirmed | `addon.py:begin_backup()` calls `stop()` |
| `18789/tcp: null` disables port mapping | Confirmed | Null filtered from Docker port bindings |
| `homeassistant_api: true` needed for Core proxy | Confirmed | Proxy checks `addon.access_homeassistant_api` |
| `SUPERVISOR_TOKEN` always injected | Confirmed | `docker/addon.py` sets both `SUPERVISOR_TOKEN` and `HASSIO_TOKEN` |

### Key Facts for Implementation

- **Node.js minimum**: `>=22.16.0` (package.json engines). Dockerfile uses Node 24 but it's not required.
- **`dangerouslyDisableDeviceAuth`** is NOT needed when served through HA ingress — ingress is HTTPS, so SubtleCrypto (required for device identity handshake) IS available in the secure context. The user's current config has this flag because they use direct HTTP access, not ingress. **Do not include in the add-on design.**
- **Token and trusted-proxy modes are mutually exclusive** — can't have both active. Use `mode: "token"` with `trustedProxies` for nginx passthrough.
- **No `/healthz` HTML bug** — the claim in earlier research was incorrect. Endpoints always return JSON.

---

---

## Part 11: External Review Response

### Decisions Made (no longer open)

| Decision | Answer | Rationale |
|---|---|---|
| Default port mapping | **Closed (`null`)** | Secure default. Users who need the companion integration enable 18789 via HA UI. |
| Integration rework | **Not now, but minimum compat fix scoped before coding** | Add-on ships first. Integration compat is a separate scope with its own exit criteria. |
| `dangerouslyDisableDeviceAuth` | **Not in base design** | Ingress is HTTPS → SubtleCrypto available → device auth works. Debug-only flag if ever needed. |
| mcporter | **Optional enhancement, not core** | Add-on boots and works without it. mcporter is Phase 6, not Phase 3. |

### Auth Decision Table

| Access Path | Auth Mechanism | Mode |
|---|---|---|
| **Control UI via ingress** | nginx injects Bearer token server-side → gateway token auth | **Steady-state** |
| **Integration direct API** | Bearer token via port 18789 (user enables port in HA UI) | **Compatibility mode** (opt-in) |
| **Terminal via ingress** | HA ingress auth (no extra auth) | **Steady-state** |
| **Direct gateway access** | Bearer token via port 18789 | **Compatibility mode** (opt-in) |
| **`dangerouslyDisableDeviceAuth`** | Disables Control UI device pairing | **Debug-only** (not in shipped config) |

### Scope Separation

**Scope A: Add-on (primary deliverable)**
- Image, S6 services, nginx, ingress, ttyd, AppArmor, persistence, token generation, config patching, watchdog, backup
- Exit criterion: add-on starts, survives restart, preserves state, exposes UI securely

**Scope B: Integration compatibility (separate deliverable)**
- Discovery, token lookup, connection path, bearer auth, endpoint enablement
- Exit criterion: integration works against the pinned OpenClaw version in compatibility mode
- Requires: minimum integration patch to use add-on Docker IP instead of localhost

**Scope C: HA MCP via mcporter (optional enhancement)**
- mcporter installation, config, daemon service
- Exit criterion: `mcporter call HA.GetLiveContext` returns data from HA
- Not required for Scope A or B

### Acceptance Test Matrix

Pin: `openclaw@2026.4.2`

| # | Area | Test | Expected | Scope |
|---|---|---|---|---|
| 1 | Boot | Add-on starts under S6, all services up | success | A |
| 2 | Restart | Restart preserves `/config/.openclaw/openclaw.json` and workspaces | success | A |
| 3 | Ingress | Landing page loads in HA sidebar | success | A |
| 4 | Ingress proxy | Gateway UI loads via nginx `/gateway/` path | success | A |
| 5 | WebSocket | Control UI WS connection works behind ingress | **gate** | A |
| 6 | Auth | Bearer token auth to gateway works in token mode | success | A |
| 7 | Health | `/healthz` returns `{"ok":true}` (JSON) | success | A |
| 8 | Health | Watchdog auto-restarts on gateway crash | success | A |
| 9 | Security | No `host_network`, no `dangerouslyDisableDeviceAuth` | success | A |
| 10 | Security | ttyd disabled by default, hidden when off | success | A |
| 11 | Backup | Cold backup/restore preserves all config | success | A |
| 12 | Config | First boot generates token, writes `openclaw.json` | success | A |
| 13 | Config | Subsequent boot does NOT overwrite user changes | success | A |
| 14 | HTTP API | `/v1/chat/completions` works with bearer token (enabled in config) | success | B |
| 15 | HTTP API | `/v1/models` works with bearer token | success | B |
| 16 | Integration | Companion integration discovers token from `/addon_configs/` | success | B |
| 17 | Integration | Integration connects via port 18789 (user-enabled) | success | B |
| 18 | MCP | `mcporter call HA.GetLiveContext` returns entity states | success | C |
| 19 | MCP | mcporter daemon starts/stops cleanly under S6 | success | C |

**Test 5 is a gate**: if WS fails through ingress, ship with landing page + "Open in new tab" button + optional direct port. Document this as compatibility mode.

### Persistence Categories (Tightened)

| Category | Location | Boot behavior | Example |
|---|---|---|---|
| **User-editable config** | `/config/.openclaw/openclaw.json`, `/config/.env`, workspaces | Never overwrite | Agent config, channels, API keys |
| **Generated secrets** | `/data/openclaw/gateway_token`, `/data/openclaw/env` | Regenerate env every boot, token only on first boot | SUPERVISOR_TOKEN, gateway token |
| **Transient runtime** | `/data/openclaw/sessions/` | Disposable, may be cleared | Session state, daemon PID files |
| **Cache** | `/data/openclaw/cache/` | Disposable | Media cache, temp files |

**Non-negotiable rule**: Init script NEVER overwrites `/config/.openclaw/openclaw.json` after first boot. On subsequent boots, it patches only: `gateway.controlUi.allowedOrigins` (HA URL may change) and `gateway.http.endpoints.chatCompletions.enabled` (must stay true for integration).

### Security Posture (Tightened)

**ttyd**: Disabled by default. When enabled, it provides a full shell inside the container to any authenticated HA user. This is a deliberate capability for power users, not a default. The shell runs as root within the container (S6 constraint). The AppArmor profile confines it, but a shell is still a shell. DOCS.md must state this clearly.

**Chromium `--no-sandbox`**: Required in containerized contexts where the kernel does not permit user namespaces. This is a known limitation of running Chromium inside Docker. The AppArmor profile partially compensates. This is an exception, not a normal configuration. It is only relevant when browser automation skills are used. Not installed by default.

---

## Final Implementation Plan (Revised)

### Phase 1: Scaffold + Ingress Prototype (GATE)
1. Repository structure, `.gitattributes`, `config.yaml`, rootfs tree, `apparmor.txt`
2. **Ingress prototype**: minimal Dockerfile + nginx + static page + proxy to OpenClaw
3. **Test 5**: Does Control UI WS work through ingress? → decision: full ingress or fallback mode
4. Pin OpenClaw version (`2026.4.2`), run tests 1, 6, 7 against it

### Phase 2: Dockerfile + S6 Core
1. Multi-stage build (`node:24-bookworm-slim` → HA `base-debian:bookworm`)
2. `npm install -g openclaw@2026.4.2` in builder
3. S6 services: `openclaw-init` (oneshot), `openclaw-gateway` (longrun), `nginx` (longrun), `ttyd` (longrun, conditional)
4. Init script: token generation, first-boot config, env file, tempio nginx render
5. Run tests 1-4, 6-13

### Phase 3: Ingress Web UI
1. Landing page (`/app/www/index.html`) with Gateway + Terminal tabs
2. nginx tempio template: proxy `/gateway/` with Bearer injection, proxy `/terminal/`
3. Run test 5 result from Phase 1 determines full UI or fallback mode

### Phase 4: Polish + Scope A Exit
1. Translations, DOCS.md, CHANGELOG.md, icons
2. AppArmor refinement
3. Full Scope A test matrix (tests 1-13) must pass
4. **Scope A ships here**

### Phase 5: Integration Compatibility (Scope B)
1. Enable `chatCompletions` in init script
2. Verify integration discovers token at expected path
3. Test integration connects via user-enabled port 18789
4. Document minimum integration patch if needed (Docker IP vs localhost)
5. Scope B test matrix (tests 14-17)

### Phase 6: HA MCP (Scope C, Optional)
1. Install mcporter in Dockerfile
2. mcporter S6 service (conditional)
3. Init script configures mcporter for `http://supervisor/core/api/mcp`
4. Scope C test matrix (tests 18-19)

---

## Next Steps
- [ ] Begin Phase 1: scaffold + ingress prototype
- [ ] Run ingress gate test (Test 5) before proceeding to Phase 2
