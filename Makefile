# Shortcuts for the app tests. Run `make` to list them.
# The bench (Python) uses `uv`, not this file.

JAVA_HOME ?= /Applications/Android Studio.app/Contents/jbr/Contents/Home
export JAVA_HOME

ADB := $(HOME)/Library/Android/sdk/platform-tools/adb
EMULATOR := $(HOME)/Library/Android/sdk/emulator/emulator
AVD ?= Medium_Phone_API_37.0
SERIAL ?= emulator-5554
DENSITY ?= 480

.PHONY: help test screenshots shot-check emulator layout check

help:
	@echo "make test        unit tests (pure logic, no device)"
	@echo "make screenshots refresh screenshot baselines (no device)"
	@echo "make shot-check  validate screenshots against the baselines (no device)"
	@echo "make emulator    boot $(AVD) and set $(DENSITY) density (360 dp wide)"
	@echo "make layout      LayoutJumpTest on a headless emulator (boots it if needed, closes it after)"
	@echo "make check       unit + shot-check, no device (the quick gate)"

test:
	./gradlew :app:testDebugUnitTest

screenshots:
	./gradlew :app:updateDebugScreenshotTest

shot-check:
	./gradlew :app:validateDebugScreenshotTest

emulator:
	$(EMULATOR) -avd $(AVD) -no-snapshot-save -no-audio -no-boot-anim -gpu swiftshader_indirect &
	$(ADB) -s $(SERIAL) wait-for-device
	@echo "waiting for boot..."
	@until [ "$$($(ADB) -s $(SERIAL) shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
	$(ADB) -s $(SERIAL) shell wm density $(DENSITY)
	@echo "ready: $(SERIAL) at $(DENSITY) density"

# Boots a headless emulator if none is up and closes it again at the end, even on failure or Ctrl-C.
layout:
	@if ! $(ADB) -s $(SERIAL) get-state >/dev/null 2>&1; then \
		trap '$(ADB) -s $(SERIAL) emu kill >/dev/null 2>&1' EXIT; \
		echo "booting $(AVD) headless..."; \
		$(EMULATOR) -avd $(AVD) -no-window -no-snapshot-save -no-audio -no-boot-anim -gpu swiftshader_indirect >/dev/null 2>&1 & \
	fi; \
	$(ADB) -s $(SERIAL) wait-for-device; \
	until [ "$$($(ADB) -s $(SERIAL) shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done; \
	$(ADB) -s $(SERIAL) shell wm density $(DENSITY); \
	ANDROID_SERIAL=$(SERIAL) ./gradlew :app:connectedDebugAndroidTest

check: test shot-check
