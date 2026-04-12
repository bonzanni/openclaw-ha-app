server {
    listen {{ .interface }}:{{ .port }} default_server;

    # First visit (no gatewayUrl param): serve the init page that
    # constructs the correct URL client-side and redirects.
    # Subsequent visits have gatewayUrl in localStorage so the
    # Control UI auto-connects.
    location = / {
        if ($arg_gatewayUrl = '') {
            alias /app/www/;
            break;
        }
        proxy_pass http://127.0.0.1:18789;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Authorization "Bearer {{ .token }}";
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
        proxy_buffering off;
        proxy_hide_header Content-Security-Policy;
        proxy_hide_header X-Frame-Options;
    }

    # All other paths — proxy to gateway (assets, API, WS)
    location / {
        proxy_pass http://127.0.0.1:18789;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Authorization "Bearer {{ .token }}";
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
        proxy_buffering off;
        proxy_hide_header Content-Security-Policy;
        proxy_hide_header X-Frame-Options;
    }

    # Terminal (ttyd) — only proxied when enabled
    {{ if .terminal_enabled }}
    location /terminal/ {
        proxy_pass http://127.0.0.1:7681/terminal/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
    }
    {{ end }}
}
