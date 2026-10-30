.PHONY: run test token token-admin token-counter token-superadmin

run:
ifeq ($(OS),Windows_NT)
	powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path .env) { (Get-Content .env -Raw | ConvertFrom-StringData).GetEnumerator() | ForEach-Object { [System.Environment]::SetEnvironmentVariable($$_.Key, $$_.Value, 'Process') } }; .\mvnw.cmd spring-boot:run"
else
	bash -c "if [ -f .env ]; then set -a; . ./.env; set +a; fi && ./mvnw spring-boot:run"
endif

test:
ifeq ($(OS),Windows_NT)
	powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path .env.test) { (Get-Content .env.test -Raw | ConvertFrom-StringData).GetEnumerator() | ForEach-Object { [System.Environment]::SetEnvironmentVariable($$_.Key, $$_.Value, 'Process') } }; .\mvnw.cmd clean test"
else
	bash -c "if [ -f .env.test ]; then set -a; . ./.env.test; set +a; fi && ./mvnw clean test"
endif

# ------------------------------------------------------------------------------
# 🔑 Keycloak JWT Token Retrieval for Swagger UI Testing
# ------------------------------------------------------------------------------
token-admin:
ifeq ($(OS),Windows_NT)
	@powershell -NoProfile -ExecutionPolicy Bypass -File scripts/get-token.ps1 -Role admin -Username "$(USER)" -Password "$(PASS)" -TenantCode "$(TENANT)"
else
	@bash scripts/get-token.sh admin "$(USER)" "$(PASS)"
endif

token-counter:
ifeq ($(OS),Windows_NT)
	@powershell -NoProfile -ExecutionPolicy Bypass -File scripts/get-token.ps1 -Role counter -Username "$(USER)" -Password "$(PASS)" -TenantCode "$(TENANT)"
else
	@bash scripts/get-token.sh counter "$(USER)" "$(PASS)"
endif

token-superadmin:
ifeq ($(OS),Windows_NT)
	@powershell -NoProfile -ExecutionPolicy Bypass -File scripts/get-token.ps1 -Role superadmin -Username "$(USER)" -Password "$(PASS)"
else
	@bash scripts/get-token.sh superadmin "$(USER)" "$(PASS)"
endif

token:
ifeq ($(OS),Windows_NT)
	@powershell -NoProfile -ExecutionPolicy Bypass -File scripts/get-token.ps1 -Role "$(ROLE)" -Username "$(USER)" -Password "$(PASS)" -TenantCode "$(TENANT)"
else
	@bash scripts/get-token.sh "$(ROLE)" "$(USER)" "$(PASS)"
endif
