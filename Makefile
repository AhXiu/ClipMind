SHELL := /bin/sh

.PHONY: test test-backend test-android vet-backend

test: test-backend test-android

test-backend:
	cd backend && go test ./...

vet-backend:
	cd backend && go vet ./...

test-android:
	cd android && ./gradlew test
