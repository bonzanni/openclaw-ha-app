# OpenClaw Add-on

Local-first personal AI gateway connecting 24+ messaging platforms to AI agents.

## Quick Start

1. Install the add-on
2. Start it — a gateway token is auto-generated
3. Open the sidebar panel to access the Gateway UI
4. Configure your agents, channels, and API keys in the Control UI

## Configuration

All OpenClaw configuration (agents, channels, models, API keys) is managed through
the **Gateway Control UI** in the sidebar panel, or by editing
`/addon_configs/<slug>/.openclaw/openclaw.json` directly via File Editor.

The add-on options below control only the add-on infrastructure:

| Option | Default | Description |
|--------|---------|-------------|
| `log_level` | `info` | Log verbosity (trace/debug/info/notice/warning/error/fatal) |
| `gateway_port` | `18789` | Internal gateway port |
| `enable_terminal` | `false` | Enable web terminal tab in sidebar |
| `enable_ha_mcp` | `true` | Enable HA MCP tools for AI agents |

## Companion Integration

For HA entities and services, install the
[OpenClaw Integration](https://github.com/techartdev/OpenClawHomeAssistantIntegration).

The integration needs direct access to port 18789. Enable it in the add-on's
**Network** settings by setting the Gateway API port.

## HA MCP Tools

To give AI agents control over your smart home:

1. In HA: Settings > Devices & Services > Add Integration > **Model Context Protocol Server**
2. Select the **Assist** API
3. Configure which entities are exposed: Settings > Voice Assistants > Expose

## Data Locations

- **User config**: `/addon_configs/<slug>/.openclaw/openclaw.json`
- **Workspaces**: `/addon_configs/<slug>/` (top-level directories)
- **Internal state**: `/data/openclaw/` (managed by add-on, do not edit)

## Security

- The gateway token is auto-generated and stored internally
- The sidebar panel is protected by HA authentication
- The terminal (when enabled) provides shell access to the container
- AI agent access to HA is controlled by HA's entity exposure settings
