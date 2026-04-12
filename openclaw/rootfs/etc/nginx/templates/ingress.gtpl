server {
    listen {{ .interface }}:{{ .port }} default_server;

    # Pre-built URLs for Control UI auto-connect redirect
    set $gateway_ws_url "{{ .gateway_ws_url }}";
    set $gateway_https_url "{{ .gateway_https_url }}";

    # Root: redirect to add gatewayUrl + token params on first visit.
    # The redirect target is the HTTPS ingress URL (same page, with params).
    # The gatewayUrl param value is the WSS URL for the Control UI.
    location = / {
        if ($arg_gatewayUrl = '') {
            return 302 $gateway_https_url?gatewayUrl=$gateway_ws_url#token={{ .token }};
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

    # All other paths: proxy to gateway
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

    # Terminal (ttyd)
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
