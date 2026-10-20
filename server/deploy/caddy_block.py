"""Writes Caddy's two zeus site blocks into the Caddyfile (replacing older ones, appending otherwise).
Only the zeus blocks are touched; install.sh backs the file up and validates before reloading."""
import re
import sys

BLOCK = """# Zeus (The owner's phone app) -> zeus-gateway. Only a client certificate from the Zeus device CA gets past TLS.
# Any X-Zeus-* header the client sends is dropped; Caddy then sets the certificate's fingerprint and a shared key
# (/etc/caddy/zeus-proxy.key, root:caddy 0640) that the gateway checks too. The gateway's socket is open to group caddy only.
zeus.example.com {
    bind 203.0.113.10
    tls {
        client_auth {
            mode require_and_verify
            trust_pool file /etc/caddy/zeus-device-ca.pem
        }
    }
    request_body {
        max_size 2GB
    }
    request_header -X-Zeus-*
    header {
        Cache-Control "no-store"
        X-Content-Type-Options "nosniff"
        Referrer-Policy "no-referrer"
        -Server
    }
    @api path /api/* /ws/*
    handle @api {
        reverse_proxy unix//run/zeus-gateway/gw.sock {
            header_up X-Zeus-Client-SHA256 {http.request.tls.client.fingerprint}
            header_up X-Zeus-Proxy-Key {file./etc/caddy/zeus-proxy.key}
            transport http {
                read_timeout 3600s
                write_timeout 3600s
            }
        }
    }
    handle {
        respond 404
    }
}
"""

HOOKS = """# Zeus webhooks -> zeus-gateway, on their own host so the main site stays strictly mTLS. No client certificate here:
# exactly POST /hooks/anthropic (Anthropic's signed webhook, checked by the gateway), body at most 64 KB, with the shared
# proxy key and no fingerprint. Every other method or path is a 404.
hooks.example.com {
    bind 203.0.113.10
    request_header -X-Zeus-*
    header {
        Cache-Control "no-store"
        X-Content-Type-Options "nosniff"
        Referrer-Policy "no-referrer"
        -Server
    }
    @hook {
        method POST
        path /hooks/anthropic
    }
    handle @hook {
        request_body {
            max_size 64KB
        }
        rewrite * /hooks/anthropic?
        reverse_proxy unix//run/zeus-gateway/gw.sock {
            header_up X-Zeus-Proxy-Key {file./etc/caddy/zeus-proxy.key}
        }
    }
    handle {
        respond 404
    }
}
"""


def put(s, host, block):
    # an older block: optional comment lines right above it, then the site through its closing brace at column 0
    pat = re.compile(r"(?:^#[^\n]*\n)*^" + re.escape(host) + r" \{\n.*?^\}\n", re.M | re.S)
    return pat.sub(lambda m: block, s, count=1) if pat.search(s) else s.rstrip("\n") + "\n\n" + block


def main(path):
    s = open(path).read()
    s = put(s, "zeus.example.com", BLOCK)
    s = put(s, "hooks.example.com", HOOKS)
    open(path, "w").write(s)
    print("zeus blocks written")


if __name__ == "__main__":
    main(sys.argv[1])
