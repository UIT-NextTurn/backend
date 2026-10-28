#!/usr/bin/env bash
# ==============================================================================
# NextTurn - Retrieve Keycloak JWT Token for Admin, Counter, or Superadmin
# ==============================================================================

ROLE="${1:-admin}"
USER="${2:-}"
PASS="${3:-}"
TENANT="${TENANT_CODE:-shop-b}"

# Load .env if present
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$BACKEND_DIR")"

if [ -f "$BACKEND_DIR/.env" ]; then
    set -a
    . "$BACKEND_DIR/.env"
    set +a
elif [ -f "$ROOT_DIR/.env" ]; then
    set -a
    . "$ROOT_DIR/.env"
    set +a
fi

KEYCLOAK_URL="${KEYCLOAK_AUTH_SERVER_URL:-http://localhost:8180}"
REALM="${KEYCLOAK_REALM:-nextturn}"
CLIENT_ID="${KEYCLOAK_CLIENT_ID:-nextturn-backend}"
CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-}"

# Fallback from ryan-a1 to localhost if needed
if ! curl -s -f "$KEYCLOAK_URL/realms/$REALM/.well-known/openid-configuration" > /dev/null 2>&1; then
    if curl -s -f "http://localhost:8180/realms/$REALM/.well-known/openid-configuration" > /dev/null 2>&1; then
        KEYCLOAK_URL="http://localhost:8180"
    fi
fi

# Normalize role
case "${ROLE,,}" in
    admin|tenant_admin)
        TARGET_ROLE="TENANT_ADMIN"
        DEFAULT_USER="admin@shop-b.internal"
        SCOPE="openid"
        ;;
    counter|counter_staff)
        TARGET_ROLE="COUNTER_STAFF"
        DEFAULT_USER="counter01@shop-b.internal"
        SCOPE="openid"
        ;;
    superadmin)
        TARGET_ROLE="SUPERADMIN"
        DEFAULT_USER="superadmin@nextturn.com"
        SCOPE="openid"
        ;;
    *)
        echo "Unknown role: $ROLE. Valid roles: admin, counter, superadmin"
        exit 1
        ;;
esac

TARGET_USER="${USER:-$DEFAULT_USER}"
TARGET_PASS="${PASS:-Nextturn123!@#}"

TOKEN_ENDPOINT="$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token"

RESPONSE=$(curl -s -X POST "$TOKEN_ENDPOINT" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=$CLIENT_ID" \
    -d "client_secret=$CLIENT_SECRET" \
    -d "username=$TARGET_USER" \
    -d "password=$TARGET_PASS" \
    -d "scope=$SCOPE")

ACCESS_TOKEN=$(echo "$RESPONSE" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
EXPIRES_IN=$(echo "$RESPONSE" | grep -o '"expires_in":[0-9]*' | cut -d':' -f2)

if [ -z "$ACCESS_TOKEN" ]; then
    echo ""
    echo "❌ [ERROR] Failed to obtain token from Keycloak for $TARGET_USER"
    echo "Response: $RESPONSE"
    echo ""
    exit 1
fi

if [ "${RAW:-false}" = "true" ]; then
    echo "$ACCESS_TOKEN"
    exit 0
fi

# Try clipboard copy (macOS pbcopy or Linux xclip)
COPIED=false
if command -v pbcopy > /dev/null 2>&1; then
    echo -n "$ACCESS_TOKEN" | pbcopy
    COPIED=true
elif command -v xclip > /dev/null 2>&1; then
    echo -n "$ACCESS_TOKEN" | xclip -selection clipboard
    COPIED=true
fi

echo ""
echo "================================================================================"
echo " [NEXTTURN] Keycloak Token Retrieved Successfully!"
echo "================================================================================"
echo " Target Role:    $TARGET_ROLE"
echo " User:           $TARGET_USER"
echo " Tenant:         $TENANT"
echo " Keycloak Realm: $REALM ($KEYCLOAK_URL)"
echo " Expires In:     ${EXPIRES_IN}s"
if [ "$COPIED" = "true" ]; then
    echo " Clipboard:      COPIED TO CLIPBOARD! Ready to paste into Swagger UI (Ctrl+V)"
else
    echo " Clipboard:      Copy token below manually."
fi
echo "--------------------------------------------------------------------------------"
echo "Access Token (Bearer):"
echo "$ACCESS_TOKEN"
echo "--------------------------------------------------------------------------------"
echo "How to use in Swagger UI (http://localhost:8080/swagger-ui.html):"
echo "  1. Open http://localhost:8080/swagger-ui.html in your browser."
echo "  2. Click the green 'Authorize' button at the top-right."
echo "  3. Paste the token into 'bearerAuth' (or BearerAuth) and click 'Authorize'."
echo "  4. Click 'Close'. All protected endpoints will now execute with $TARGET_ROLE role!"
echo ""
echo "How to use with cURL:"
echo "  curl -H \"Authorization: Bearer $ACCESS_TOKEN\" http://localhost:8080/api/v1/auth/staff"
echo "================================================================================"
echo ""
