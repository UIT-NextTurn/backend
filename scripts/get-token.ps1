<#
.SYNOPSIS
    Retrieves OAuth2 JWT Bearer tokens from Keycloak for NextTurn client roles (SUPERADMIN, TENANT_ADMIN, COUNTER_STAFF).
.DESCRIPTION
    Interacts with Keycloak IAM using Direct Access Grants (password grant).
    Auto-provisions the test user and assigns the required client role via Keycloak Admin API if not present.
    Copies the access token to clipboard for quick pasting into Swagger UI (http://localhost:8080/swagger-ui.html).
.EXAMPLE
    .\get-token.ps1 -Role admin
    .\get-token.ps1 -Role counter
    .\get-token.ps1 -Role superadmin
    .\get-token.ps1 -Role admin -Username "myadmin@clinic.com" -Password "Secret123!"
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("admin", "counter", "superadmin", "tenant_admin", "counter_staff")]
    [string]$Role = "admin",

    [Parameter(Position = 1)]
    [string]$Username = "",

    [Parameter(Position = 2)]
    [string]$Password = "",

    [string]$TenantCode = "",
    [string]$ServerUrl = "",
    [string]$ClientId = "",
    [string]$ClientSecret = "",
    [string]$Realm = "",
    [switch]$Raw
)

$ErrorActionPreference = "Stop"

# ------------------------------------------------------------------------------
# 1. Resolve Project Root and Load .env
# ------------------------------------------------------------------------------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Split-Path -Parent $scriptDir
$rootDir = Split-Path -Parent $backendDir

function Load-EnvFile($filePath) {
    if (Test-Path $filePath) {
        Get-Content $filePath | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $parts = $line.Split("=", 2)
                $key = $parts[0].Trim()
                $val = $parts[1].Trim()
                # Remove quotes and escaped characters
                $val = $val.Trim('"', "'").Replace('\#', '#')
                if (-not [System.Environment]::GetEnvironmentVariable($key)) {
                    [System.Environment]::SetEnvironmentVariable($key, $val, 'Process')
                }
            }
        }
    }
}

Load-EnvFile (Join-Path $backendDir ".env")
Load-EnvFile (Join-Path $rootDir ".env")

# Fallback Configuration
if (-not $ServerUrl) {
    $ServerUrl = [System.Environment]::GetEnvironmentVariable("KEYCLOAK_AUTH_SERVER_URL")
    if (-not $ServerUrl) { $ServerUrl = "http://localhost:8180" }
}
if (-not $ClientId) {
    $ClientId = [System.Environment]::GetEnvironmentVariable("KEYCLOAK_CLIENT_ID")
    if (-not $ClientId) { $ClientId = "nextturn-backend" }
}
if (-not $ClientSecret) {
    $ClientSecret = [System.Environment]::GetEnvironmentVariable("KEYCLOAK_CLIENT_SECRET")
}
if (-not $Realm) {
    $Realm = [System.Environment]::GetEnvironmentVariable("KEYCLOAK_REALM")
    if (-not $Realm) { $Realm = "nextturn" }
}

# ------------------------------------------------------------------------------
# 2. Normalize Role and Defaults
# ------------------------------------------------------------------------------
$normalizedRole = $Role.ToLower()
$targetRole = ""
$defaultUser = ""
$scope = "openid"

# Dynamically infer TenantCode from Username pattern if not explicitly passed
if (-not $TenantCode -and $Username) {
    if ($Username -match "^(?:admin|counter|staff)[-_]([a-zA-Z0-9_-]+)$") {
        $TenantCode = $Matches[1]
    } elseif ($Username -match "@([a-zA-Z0-9_-]+)\.[a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)?$") {
        $TenantCode = $Matches[1]
    }
}

switch -Regex ($normalizedRole) {
    "^(admin|tenant_admin)$" {
        $targetRole = "TENANT_ADMIN"
        $defaultUser = "admin@$TenantCode.internal"
        $scope = "openid"
    }
    "^(counter|counter_staff)$" {
        $targetRole = "COUNTER_STAFF"
        $defaultUser = "counter01@$TenantCode.internal"
        $scope = "openid"
    }
    "^superadmin$" {
        $targetRole = "SUPERADMIN"
        $defaultUser = "superadmin@nextturn.com"
        $TenantCode = ""
        $scope = "openid"
    }
}

if (-not $Username) { $Username = $defaultUser }
if (-not $Password) { $Password = "Nextturn123!@#" }

# ------------------------------------------------------------------------------
# 3. Test Keycloak Reachability (fallback from ryan-a1 to localhost if needed)
# ------------------------------------------------------------------------------
function Test-KeycloakEndpoint($url, $realmName) {
    try {
        $testUri = "$url/realms/$realmName/.well-known/openid-configuration"
        $resp = Invoke-RestMethod -Uri $testUri -Method Get -TimeoutSec 3 -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

$isReachable = Test-KeycloakEndpoint $ServerUrl $Realm
if (-not $isReachable) {
    # If URL contains hostname like ryan-a1, attempt localhost fallback
    if ($ServerUrl -match "ryan-a1|127\.0\.0\.1") {
        $fallbackUrl = "http://localhost:8180"
        if (Test-KeycloakEndpoint $fallbackUrl $Realm) {
            $ServerUrl = $fallbackUrl
            $isReachable = $true
        }
    }
}

if (-not $isReachable) {
    Write-Host ""
    Write-Host " [ERROR] Cannot reach Keycloak at $ServerUrl" -ForegroundColor Red
    Write-Host " Please make sure Keycloak is running:" -ForegroundColor Yellow
    Write-Host "   1. Start Docker Desktop (if not already running)" -ForegroundColor Yellow
    Write-Host "   2. Run 'make up-keycloak' inside infrastructure/ directory" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

# ------------------------------------------------------------------------------
# 4. Helper: Auto-ensure User Exists via Service Account (Admin REST API)
# ------------------------------------------------------------------------------
function Ensure-UserAndRole() {
    try {
        $adminToken = $null

        # 4.1 Keycloak Bootstrap Admin in master realm (has full permissions on all realms)
        $masterAdminUser = [System.Environment]::GetEnvironmentVariable("KEYCLOAK_ADMIN_USER")
        if (-not $masterAdminUser) { $masterAdminUser = "nextturn" }
        $masterAdminPass = [System.Environment]::GetEnvironmentVariable("KEYCLOAK_ADMIN_PASSWORD")
        if (-not $masterAdminPass) { $masterAdminPass = "Nextturn123!@#" }

        try {
            $masterTokenEndpoint = "$ServerUrl/realms/master/protocol/openid-connect/token"
            $masterBody = @{
                grant_type = "password"
                client_id  = "admin-cli"
                username   = $masterAdminUser
                password   = $masterAdminPass
            }
            $masterResp = Invoke-RestMethod -Uri $masterTokenEndpoint -Method Post -Body $masterBody -ContentType "application/x-www-form-urlencoded" -ErrorAction Stop
            $adminToken = $masterResp.access_token
        } catch {
            # Try Service Account Token as fallback
            if ($ClientSecret) {
                try {
                    $tokenEndpoint = "$ServerUrl/realms/$Realm/protocol/openid-connect/token"
                    $serviceBody = @{
                        grant_type    = "client_credentials"
                        client_id     = $ClientId
                        client_secret = $ClientSecret
                    }
                    $saResp = Invoke-RestMethod -Uri $tokenEndpoint -Method Post -Body $serviceBody -ContentType "application/x-www-form-urlencoded" -ErrorAction Stop
                    $adminToken = $saResp.access_token
                } catch {
                }
            }
        }

        if (-not $adminToken) { return }

        $headers = @{
            Authorization = "Bearer $adminToken"
            "Content-Type" = "application/json"
        }

        # 4.1.1 Ensure Keycloak User Profile allows tenant_code attribute
        try {
            $profile = Invoke-RestMethod -Uri "$ServerUrl/admin/realms/$Realm/users/profile" -Method Get -Headers $headers -ErrorAction SilentlyContinue
            if ($profile) {
                $hasTenantAttr = $false
                foreach ($a in $profile.attributes) {
                    if ($a.name -eq "tenant_code") { $hasTenantAttr = $true; break }
                }
                if (-not $hasTenantAttr) {
                    $tenantAttr = @{
                        name = "tenant_code"
                        displayName = "Tenant Code"
                        permissions = @{
                            view = @("admin", "user")
                            edit = @("admin")
                        }
                        multivalued = $false
                    }
                    $profile | Add-Member -MemberType NoteProperty -Name "unmanagedAttributePolicy" -Value "ENABLED" -Force
                    $profile.attributes = @($profile.attributes) + $tenantAttr
                    $profileJson = $profile | ConvertTo-Json -Depth 10
                    Invoke-RestMethod -Uri "$ServerUrl/admin/realms/$Realm/users/profile" -Method Put -Headers $headers -Body $profileJson -ErrorAction SilentlyContinue | Out-Null
                }
            }
        } catch {}

        # 4.2 Ensure Client has Direct Access Grants Enabled
        $clientsUri = "$ServerUrl/admin/realms/$Realm/clients?clientId=$ClientId"
        $clientList = Invoke-RestMethod -Uri $clientsUri -Method Get -Headers $headers -ErrorAction SilentlyContinue
        if ($clientList -and $clientList.Count -gt 0) {
            $clientUuid = $clientList[0].id
            if (-not $clientList[0].directAccessGrantsEnabled) {
                $clientObj = $clientList[0]
                $clientObj.directAccessGrantsEnabled = $true
                $updatePayload = $clientObj | ConvertTo-Json -Depth 10
                Invoke-RestMethod -Uri "$ServerUrl/admin/realms/$Realm/clients/$clientUuid" -Method Put -Headers $headers -Body $updatePayload -ErrorAction SilentlyContinue | Out-Null
            }

            # 4.2.1 Ensure Protocol Mapper for tenant_code exists on client
            $mappersUri = "$ServerUrl/admin/realms/$Realm/clients/$clientUuid/protocol-mappers/models"
            $existingMappers = Invoke-RestMethod -Uri $mappersUri -Method Get -Headers $headers -ErrorAction SilentlyContinue
            $hasTenantMapper = $false
            if ($existingMappers) {
                foreach ($m in $existingMappers) {
                    if ($m.name -eq "tenant-code-mapper" -or ($m.config -and $m.config."claim.name" -eq "tenant_code")) {
                        $hasTenantMapper = $true
                        break
                    }
                }
            }
            if (-not $hasTenantMapper) {
                $mapperPayload = @{
                    name           = "tenant-code-mapper"
                    protocol       = "openid-connect"
                    protocolMapper = "oidc-usermodel-attribute-mapper"
                    consentRequired = $false
                    config         = @{
                        "user.attribute"         = "tenant_code"
                        "claim.name"             = "tenant_code"
                        "jsonType.label"         = "String"
                        "id.token.claim"         = "true"
                        "access.token.claim"     = "true"
                        "userinfo.token.claim"   = "true"
                        "multivalued"            = "false"
                        "aggregate.attrs"        = "false"
                    }
                } | ConvertTo-Json -Depth 5
                Invoke-RestMethod -Uri $mappersUri -Method Post -Headers $headers -Body $mapperPayload -ErrorAction SilentlyContinue | Out-Null
            }

            # 4.3 Search for User (first by username, then by email)
            $usersUri = "$ServerUrl/admin/realms/$Realm/users?username=$([System.Web.HttpUtility]::UrlEncode($Username))&exact=true"
            $userList = Invoke-RestMethod -Uri $usersUri -Method Get -Headers $headers -ErrorAction SilentlyContinue
            if (-not $userList -or $userList.Count -eq 0) {
                $usersUri = "$ServerUrl/admin/realms/$Realm/users?email=$([System.Web.HttpUtility]::UrlEncode($Username))&exact=true"
                $userList = Invoke-RestMethod -Uri $usersUri -Method Get -Headers $headers -ErrorAction SilentlyContinue
            }

            if ($userList -and $userList.Count -gt 0) {
                $userUuid = $userList[0].id
                $existingUser = $userList[0]
                $needsUpdate = $false

                if ($TenantCode) {
                    $existingUser | Add-Member -MemberType NoteProperty -Name "attributes" -Value @{ tenant_code = @($TenantCode) } -Force
                    $needsUpdate = $true
                }
                if ($existingUser.requiredActions -and $existingUser.requiredActions.Count -gt 0) {
                    $existingUser.requiredActions = @()
                    $existingUser.emailVerified = $true
                    $needsUpdate = $true
                }
                if ($needsUpdate) {
                    $userUpdateJson = $existingUser | ConvertTo-Json -Depth 10
                    Invoke-RestMethod -Uri "$ServerUrl/admin/realms/$Realm/users/$userUuid" -Method Put -Headers $headers -Body $userUpdateJson -ErrorAction SilentlyContinue | Out-Null
                }

                # Sync/reset password to match provided parameter
                if ($Password) {
                    $pwdPayload = @{
                        type      = "password"
                        value     = $Password
                        temporary = $false
                    } | ConvertTo-Json
                    Invoke-RestMethod -Uri "$ServerUrl/admin/realms/$Realm/users/$userUuid/reset-password" -Method Put -Headers $headers -Body $pwdPayload -ErrorAction SilentlyContinue | Out-Null
                }
            } else {
                # Create user
                $newUser = @{
                    username      = $Username
                    email         = (if ($Username.Contains("@")) { $Username } else { "$Username@$TenantCode.internal" })
                    enabled       = $true
                    emailVerified = $true
                    firstName     = ($Username.Split('@')[0])
                    lastName      = $targetRole
                    attributes    = (if ($TenantCode) { @{ tenant_code = @($TenantCode) } } else { @{} })
                    credentials   = @(
                        @{
                            type      = "password"
                            value     = $Password
                            temporary = $false
                        }
                    )
                } | ConvertTo-Json -Depth 5

                $createResp = Invoke-WebRequest -Uri "$ServerUrl/admin/realms/$Realm/users" -Method Post -Headers $headers -Body $newUser -ErrorAction SilentlyContinue
                if ($createResp.Headers.Location) {
                    $loc = $createResp.Headers.Location
                    $userUuid = $loc.Substring($loc.LastIndexOf('/') + 1)
                } else {
                    $reSearch = Invoke-RestMethod -Uri $usersUri -Method Get -Headers $headers -ErrorAction SilentlyContinue
                    if ($reSearch -and $reSearch.Count -gt 0) {
                        $userUuid = $reSearch[0].id
                    }
                }
            }

            # 4.4 Assign Client Role to User
            if ($userUuid -and $clientUuid) {
                $roleUri = "$ServerUrl/admin/realms/$Realm/clients/$clientUuid/roles/$targetRole"
                $roleObj = Invoke-RestMethod -Uri $roleUri -Method Get -Headers $headers -ErrorAction SilentlyContinue
                if ($roleObj) {
                    $roleJson = $roleObj | ConvertTo-Json -Depth 5 -Compress
                    $rolePayload = "[$roleJson]"
                    $mapUri = "$ServerUrl/admin/realms/$Realm/users/$userUuid/role-mappings/clients/$clientUuid"
                    Invoke-RestMethod -Uri $mapUri -Method Post -Headers $headers -Body $rolePayload -ErrorAction SilentlyContinue | Out-Null
                }

                # Also assign realm role if SUPERADMIN
                if ($targetRole -eq "SUPERADMIN") {
                    $realmRoleUri = "$ServerUrl/admin/realms/$Realm/roles/ROLE_SUPERADMIN"
                    $realmRoleObj = Invoke-RestMethod -Uri $realmRoleUri -Method Get -Headers $headers -ErrorAction SilentlyContinue
                    if ($realmRoleObj) {
                        $rJson = $realmRoleObj | ConvertTo-Json -Depth 5 -Compress
                        $rPayload = "[$rJson]"
                        Invoke-RestMethod -Uri "$ServerUrl/admin/realms/$Realm/users/$userUuid/role-mappings/realm" -Method Post -Headers $headers -Body $rPayload -ErrorAction SilentlyContinue | Out-Null
                    }
                }
            }
        }
    } catch {
        # Auto-provisioning note: non-fatal, continues to direct password grant exchange
    }
}

Ensure-UserAndRole

# ------------------------------------------------------------------------------
# 5. Request User Token via Password Grant
# ------------------------------------------------------------------------------
$tokenEndpoint = "$ServerUrl/realms/$Realm/protocol/openid-connect/token"
$body = @{
    grant_type = "password"
    client_id  = $ClientId
    username   = $Username
    password   = $Password
    scope      = $scope
}
if ($ClientSecret) {
    $body["client_secret"] = $ClientSecret
}

try {
    $tokenResponse = Invoke-RestMethod -Uri $tokenEndpoint -Method Post -Body $body -ContentType "application/x-www-form-urlencoded" -ErrorAction Stop
} catch {
    $errBody = $_.ErrorDetails.Message
    Write-Host ""
    Write-Host " [ERROR] Failed to obtain token from Keycloak for user [$Username]" -ForegroundColor Red
    if ($errBody) {
        Write-Host " Details: $errBody" -ForegroundColor Yellow
    } else {
        Write-Host " Status: $($_.Exception.Message)" -ForegroundColor Yellow
    }
    Write-Host ""
    exit 1
}

$accessToken = $tokenResponse.access_token
$expiresIn = $tokenResponse.expires_in

# Copy to clipboard if possible
$copiedToClipboard = $false
try {
    Set-Clipboard -Value $accessToken -ErrorAction SilentlyContinue
    $copiedToClipboard = $true
} catch {
    $copiedToClipboard = $false
}

# ------------------------------------------------------------------------------
# 6. Output Token
# ------------------------------------------------------------------------------
if ($Raw) {
    Write-Output $accessToken
    exit 0
}

Write-Host "COPIED TO CLIPBOARD!" -ForegroundColor Green
