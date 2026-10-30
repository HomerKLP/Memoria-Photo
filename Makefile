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
KEYSTORE ?= $(HOME)/keys/memoria-release.jks
# The release key's SHA-256; an APK signed with any other key (the debug key above all) is deleted.
KEY_SHA256 := bc1c500550d9e3bd73e27f44132877189329f88875ace0bd6049a828e792b240
VERSION := $(shell sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
APK := app/build/outputs/apk/release/memoria-$(VERSION).apk

.PHONY: help install apk release pull-db push-models test screenshots shot-check emulator layout behavior perf check

help:
	@echo "make install     build debug (app.memoria.debug, next to release), install on the phone over USB, open it (stops if the build fails)"
	@echo "make apk         build release, sign with the release key (asks its password), check the signer; the file to publish"
	@echo "make release     make apk, then install on the phone, compile with the profile, open (for speed checks, keeps the data)"
	@echo "make pull-db     copy the debug app's DB (+ -wal, -shm) to testdata/out/db/; the last copy moves to db/prev-<time>/"
	@echo "make push-models models/*.tflite to the debug app under the names it reads, rw-rw-rw-, dates kept (no download needed)"
	@echo "make test        unit tests (pure logic, no device); one class: make test T=DetailsGpsTest"
	@echo "make screenshots refresh screenshot baselines (no device)"
	@echo "make shot-check  validate screenshots against the baselines (no device)"
	@echo "make emulator    boot $(AVD) and set $(DENSITY) density (360 dp wide)"
	@echo "make layout      every androidTest on a headless emulator (boots it if needed, closes it after); some: make layout T='BehaviorTest#search_|DaoTest'"
	@echo "make behavior    BehaviorTest on the same emulator (boots it if needed, closes it after)"
	@echo "make perf        cold start + janky frames per screen on the phone's release build (make release first; keeps the data)"
	@echo "make check       unit + shot-check, no device (the quick gate)"

# No pipes here: a pipe hides gradle's exit code, and a failed build would install the old APK.
install:
	./gradlew :app:assembleDebug
	$(ADB) -d install -r app/build/outputs/apk/debug/app-debug.apk
	$(ADB) -d shell am start -n app.memoria.debug/app.memoria.MainActivity

apk:
	./gradlew :app:assembleRelease
	rm -f $(APK)
	PATH="$(JAVA_HOME)/bin:$$PATH" $(APKSIGNER) sign --ks $(KEYSTORE) --out $(APK) app/build/outputs/apk/release/app-release-unsigned.apk
	PATH="$(JAVA_HOME)/bin:$$PATH" $(APKSIGNER) verify --print-certs $(APK) | grep -qi 'SHA-256 digest: $(KEY_SHA256)' \
		|| { rm -f $(APK); echo "Not signed with the release key, APK deleted"; exit 1; }
	@echo "Signed with the release key: $(APK)"

release: apk
	$(ADB) -d install -r $(APK)
	# A fresh install runs uncompiled, every frame ~2x slower, until Android compiles it overnight; compile it now with the profile.
	$(ADB) -d shell am start -W -n app.memoria/.MainActivity
	$(ADB) -d shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n app.memoria/androidx.profileinstaller.ProfileInstallReceiver
	$(ADB) -d shell cmd package compile -f -m speed-profile app.memoria
	$(ADB) -d shell am start -n app.memoria/.MainActivity

# run-as only works on a debug build; checked first so a failed read never overwrites the local copy.
# The last copy moves to db/prev-<time>/ first: pulling a wiped phone must not replace the only backup.
pull-db:
	$(ADB) -d shell run-as app.memoria.debug true
	mkdir -p testdata/out/db
	if [ -f testdata/out/db/memoria.db ]; then d=testdata/out/db/prev-$$(date +%Y%m%d-%H%M%S); mkdir $$d && mv testdata/out/db/memoria.db* $$d/; fi
	for f in memoria.db memoria.db-wal memoria.db-shm; do $(ADB) -d exec-out run-as app.memoria.debug cat databases/$$f > testdata/out/db/$$f || exit 1; done

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
# The close waits until it is gone, or a run right after sees it still up and waits for it forever.
# UI_FILTER narrows the run to one class; `make behavior` sets it (target-specific, inherited here).
# T is a regex on "Class#method"; a comma list in `class` runs only its first item.
layout:
	@if ! $(ADB) -s $(SERIAL) get-state >/dev/null 2>&1; then \
		trap '$(ADB) -s $(SERIAL) emu kill >/dev/null 2>&1; while $(ADB) -s $(SERIAL) get-state >/dev/null 2>&1; do sleep 1; done' EXIT; \
		echo "booting $(AVD) headless..."; \
		$(EMULATOR) -avd $(AVD) -no-window -no-snapshot-save -no-audio -no-boot-anim -gpu swiftshader_indirect >/dev/null 2>&1 & \
	fi; \
	$(ADB) -s $(SERIAL) wait-for-device; \
	until [ "$$($(ADB) -s $(SERIAL) shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done; \
	$(ADB) -s $(SERIAL) shell wm density $(DENSITY); \
	ANDROID_SERIAL=$(SERIAL) ./gradlew :app:connectedDebugAndroidTest $(UI_FILTER) $(if $(T),'-Pandroid.testInstrumentationRunnerArguments.tests_regex=.*($(T)).*')

# Just BehaviorTest, on the same emulator as `make layout`. `make layout` runs both.
behavior: UI_FILTER = -Pandroid.testInstrumentationRunnerArguments.class=app.memoria.BehaviorTest
behavior: layout

# Taps and dumpsys on the installed app, so its data stays (a Gradle device run uninstalls the app).
perf:
	uv run bench/perf.py

check: test shot-check

# bench file:app name (Models.kt). adb makes them shell's, so the app reads them only as rw-rw-rw- (bugs.md, "Memoria Debug never auto-indexed").
MODEL_NAMES := yolov5m_raw_fp16:detector_raw adaface_ir_101_fp16:recognizer ediffiqa_t_fp16:quality siglip2_b16_fp16:tagger yolo26_m_fp16:objects noodle_s_fp16:pets
push-models:
	@for m in $(MODEL_NAMES); do \
		$(ADB) -d push -a models/$${m%%:*}.tflite /sdcard/Android/data/app.memoria.debug/files/models/$${m##*:}.tflite || exit 1; \
		$(ADB) -d shell chmod 666 /sdcard/Android/data/app.memoria.debug/files/models/$${m##*:}.tflite || exit 1; \
	done
