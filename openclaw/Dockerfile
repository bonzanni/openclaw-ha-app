# ==============================================================================
# Stage 1: Install OpenClaw from npm using official Node.js image
# ==============================================================================
ARG OPENCLAW_VERSION=2026.4.2

FROM node:24-bookworm-slim AS openclaw-build

ARG OPENCLAW_VERSION

# Install OpenClaw globally — this gives us the openclaw CLI + all dependencies
RUN npm install -g "openclaw@${OPENCLAW_VERSION}" \
    && npm cache clean --force

# ==============================================================================
# Stage 2: Runtime container on HA base image
# ==============================================================================
FROM ghcr.io/home-assistant/base-debian:trixie

# Copy Node.js runtime from build stage (node, npm, npx + all modules)
COPY --from=openclaw-build /usr/local/bin/node /usr/local/bin/node
COPY --from=openclaw-build /usr/local/bin/npm /usr/local/bin/npm
COPY --from=openclaw-build /usr/local/bin/npx /usr/local/bin/npx
COPY --from=openclaw-build /usr/local/lib/node_modules /usr/local/lib/node_modules

# Create openclaw symlink in PATH
RUN ln -sf /usr/local/lib/node_modules/openclaw/openclaw.mjs /usr/local/bin/openclaw \
    && chmod +x /usr/local/bin/openclaw

# Install runtime dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        nginx \
        curl \
        openssl \
        procps \
        jq \
        libwebsockets-dev \
        libjson-c-dev \
        libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# Install ttyd (not packaged in Debian)
ARG TTYD_VERSION=1.7.7
RUN ARCH="$(dpkg --print-architecture)" \
    && if [ "$ARCH" = "amd64" ]; then TTYD_ARCH="x86_64"; \
       elif [ "$ARCH" = "arm64" ]; then TTYD_ARCH="aarch64"; \
       else echo "Unsupported arch: $ARCH"; exit 1; fi \
    && curl -fsSL "https://github.com/tsl0922/ttyd/releases/download/${TTYD_VERSION}/ttyd.${TTYD_ARCH}" \
        -o /usr/bin/ttyd \
    && chmod +x /usr/bin/ttyd

# Create persistent data directories
RUN mkdir -p /data/openclaw

# Copy S6 service definitions, nginx templates, and landing page
COPY rootfs /

# Ensure all S6 scripts are executable (Windows builds lose execute bits)
RUN chmod +x /etc/s6-overlay/scripts/*.sh \
    && find /etc/s6-overlay/s6-rc.d -name "run" -o -name "finish" -o -name "up" | xargs chmod +x
