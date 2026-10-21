ifeq ($(OS),Windows_NT)
	MVNW := mvnw.cmd
else
	MVNW := ./mvnw
endif

# Load environment variables from .env if present
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

.PHONY: run
run:
	$(MVNW) spring-boot:run
