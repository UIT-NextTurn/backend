ifeq ($(OS),Windows_NT)
	MVNW := mvnw.cmd
else
	MVNW := ./mvnw
endif

.PHONY: run
run:
	$(MVNW) spring-boot:run
