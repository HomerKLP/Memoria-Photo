# Shortcuts for the app tests. Run `make` to list them.
# The bench (Python) uses `uv`, not this file.

JAVA_HOME ?= /Applications/Android Studio.app/Contents/jbr/Contents/Home
export JAVA_HOME

ADB := $(HOME)/Library/Android/sdk/platform-tools/adb
APKSIGNER := $(HOME)/Library/Android/sdk/build-tools/36.0.0/apksigner
EMULATOR := $(HOME)/Library/Android/sdk/emulator/emulator
AVD ?= Medium_Phone_API_37.0
SERIAL ?= emulator-5554
DENSITY ?= 480

.PHONY: help install release pull-db test screenshots shot-check emulator layout behavior perf check

help:
	@echo "make install     build debug, install on the phone over USB, open the app (stops if the build fails)"
	@echo "make release     build release, sign with the debug key, install, open (for speed checks, keeps the data)"
	@echo "make pull-db     copy the debug app's DB (+ -wal, -shm) to testdata/out/db/; the last copy moves to db/prev-<time>/"
	@echo "make test        unit tests (pure logic, no device); one class: make test T=DetailsGpsTest"
	@echo "make screenshots refresh screenshot baselines (no device)"
	@echo "make shot-check  validate screenshots against the baselines (no device)"
	@echo "make emulator    boot $(AVD) and set $(DENSITY) density (360 dp wide)"
	@echo "make layout      LayoutJumpTest on a headless emulator (boots it if needed, closes it after)"
	@echo "make behavior    BehaviorTest on the same emulator (boots it if needed, closes it after)"
	@echo "make perf        Macrobenchmark startup + scroll on $(SERIAL) (release; numbers, not a pass/fail)"
	@echo "make check       unit + shot-check, no device (the quick gate)"

# No pipes here: a pipe hides gradle's exit code, and a failed build would install the old APK.
install:
	./gradlew :app:assembleDebug
	$(ADB) -d install -r app/build/outputs/apk/debug/app-debug.apk
	$(ADB) -d shell am start -n app.memoria/.MainActivity

release:
	./gradlew :app:assembleRelease
	PATH="$(JAVA_HOME)/bin:$$PATH" $(APKSIGNER) sign --ks $(HOME)/.android/debug.keystore --ks-pass pass:android \
		--out app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
	$(ADB) -d install -r app/build/outputs/apk/release/app-release.apk
	$(ADB) -d shell am start -n app.memoria/.MainActivity

# run-as only works on a debug build; checked first so a failed read never overwrites the local copy.
# The last copy moves to db/prev-<time>/ first: pulling a wiped phone must not replace the only backup.
pull-db:
	$(ADB) -d shell run-as app.memoria true
	mkdir -p testdata/out/db
	if [ -f testdata/out/db/memoria.db ]; then d=testdata/out/db/prev-$$(date +%Y%m%d-%H%M%S); mkdir $$d && mv testdata/out/db/memoria.db* $$d/; fi
	for f in memoria.db memoria.db-wal memoria.db-shm; do $(ADB) -d exec-out run-as app.memoria cat databases/$$f > testdata/out/db/$$f || exit 1; done

test:
	./gradlew :app:testDebugUnitTest $(if $(T),--tests '$(T)')

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
# UI_FILTER narrows the run to one class; `make behavior` sets it (target-specific, inherited here).
layout:
	@if ! $(ADB) -s $(SERIAL) get-state >/dev/null 2>&1; then \
		trap '$(ADB) -s $(SERIAL) emu kill >/dev/null 2>&1' EXIT; \
		echo "booting $(AVD) headless..."; \
		$(EMULATOR) -avd $(AVD) -no-window -no-snapshot-save -no-audio -no-boot-anim -gpu swiftshader_indirect >/dev/null 2>&1 & \
	fi; \
	$(ADB) -s $(SERIAL) wait-for-device; \
	until [ "$$($(ADB) -s $(SERIAL) shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done; \
	$(ADB) -s $(SERIAL) shell wm density $(DENSITY); \
	ANDROID_SERIAL=$(SERIAL) ./gradlew :app:connectedDebugAndroidTest $(UI_FILTER)

# Just BehaviorTest, on the same emulator as `make layout`. `make layout` runs both.
behavior: UI_FILTER = -Pandroid.testInstrumentationRunnerArguments.class=app.memoria.BehaviorTest
behavior: layout

# Macrobenchmark numbers (startup + scroll) on $(SERIAL): an emulator by default, the phone with SERIAL=<id>.
# Runs on a release build; the tests grant the app's permissions themselves, so no dialogs appear.
perf:
	ANDROID_SERIAL=$(SERIAL) ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest

check: test shot-check
