.PHONY: run
run:
ifeq ($(OS),Windows_NT)
	powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path .env) { (Get-Content .env -Raw | ConvertFrom-StringData).GetEnumerator() | ForEach-Object { [System.Environment]::SetEnvironmentVariable($$_.Key, $$_.Value, 'Process') } }; .\mvnw.cmd spring-boot:run"
else
	bash -c "if [ -f .env ]; then set -a; . ./.env; set +a; fi && ./mvnw spring-boot:run"
endif
