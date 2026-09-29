.PHONY: all aar build assemble release test clean

export PATH := $(HOME)/go/bin:$(PATH)
export ANDROID_HOME := $(if $(ANDROID_HOME),$(ANDROID_HOME),$(if $(ANDROID_SDK_ROOT),$(ANDROID_SDK_ROOT),$(HOME)/Android/Sdk))
export ANDROID_NDK_HOME := $(if $(ANDROID_NDK_HOME),$(ANDROID_NDK_HOME),$(shell find $(ANDROID_HOME)/ndk -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1))

ANDROID_API ?= 24
ANDROID_PKG ?= io.ladderairport.agent
# 同 APK：Agent（FRP 节点）+ 本机 VPN 客户端。with_gvisor 供 TUN 使用。
ANDROID_TAGS ?= with_quic,with_utls,with_android,with_gvisor
ANDROID_TARGETS ?= android/arm64,android/amd64
GOMOBILE ?= $(shell which gomobile 2>/dev/null || echo $(HOME)/go/bin/gomobile)

# Main monorepo is linked as a git submodule at ./LadderAirport
LADDER_ROOT ?= $(CURDIR)/LadderAirport
ifeq ($(wildcard $(LADDER_ROOT)/agent/go.mod),)
  # Fallback to sibling repo if submodule not initialized
  LADDER_ROOT := $(abspath $(CURDIR)/../LadderAirport)
endif

VERSION ?= $(shell git -C $(LADDER_ROOT) describe --tags --abbrev=0 2>/dev/null || echo v0.15.9)
GIT_COMMIT ?= $(shell git -C $(LADDER_ROOT) rev-parse --short HEAD 2>/dev/null || echo unknown)
BUILD_TIME ?= $(shell date -u +%Y-%m-%dT%H:%M:%SZ)
SINGBOX_VERSION ?= $(shell tag=$$(git -C $(LADDER_ROOT)/agent/sing-box describe --tags 2>/dev/null) && echo "$${tag}" | sed 's/^v//' || echo 1.12.22)

VERSION_LDFLAGS = \
	-X 'github.com/ladderairport/agent/internal/version.Version=$(VERSION)' \
	-X 'github.com/ladderairport/agent/internal/version.Commit=$(GIT_COMMIT)' \
	-X 'github.com/ladderairport/agent/internal/version.BuiltAt=$(BUILD_TIME)' \
	-X 'github.com/sagernet/sing-box/constant.Version=$(SINGBOX_VERSION)'

all: aar assemble

help:
	@echo "LadderAirport Android"
	@echo ""
	@echo "  make check-src          Verify ./LadderAirport submodule is checked out"
	@echo "  make sync-submodule     git submodule update --init (submodule + deps)"
	@echo "  make aar                Build ladderagent.aar from core/mobile"
	@echo "  make assemble           Build debug APK"
	@echo "  make release            Build release APK"
	@echo "  make test               Run unit tests"
	@echo "  LADDER_ROOT=$(LADDER_ROOT)"
	@echo ""
	@echo "Clone this repo with submodules:"
	@echo "  git clone --recurse-submodules https://github.com/LadderAirport/LadderAirportAndroid.git"

sync-submodule:
	git submodule update --init LadderAirport
	git -C LadderAirport submodule update --init agent/frp agent/sing-box

check-src:
	@test -f "$(LADDER_ROOT)/agent/go.mod" || { \
		echo "missing $(LADDER_ROOT)/agent/go.mod — run: make sync-submodule"; \
		exit 1; \
	}
	@test -d "$(LADDER_ROOT)/agent/sing-box" || { \
		echo "missing sing-box under submodule — run: make sync-submodule"; \
		exit 1; \
	}
	@test -d "$(LADDER_ROOT)/agent/frp" || { \
		echo "missing frp under submodule — run: make sync-submodule"; \
		exit 1; \
	}
	@test -d "$(LADDER_ROOT)/pkg" -a -d "$(LADDER_ROOT)/proto" || { \
		echo "missing pkg/ or proto/ under $(LADDER_ROOT)"; \
		exit 1; \
	}
	@echo "OK: LADDER_ROOT=$(LADDER_ROOT) (git submodule)"
	@git -C "$(LADDER_ROOT)" describe --tags --always 2>/dev/null || true
	@git -C "$(LADDER_ROOT)" rev-parse --short HEAD 2>/dev/null || true

aar: check-src
	@echo "==> Building ladderagent.aar from core/mobile (version: $(VERSION))..."
	mkdir -p app/libs
	cd core && GOWORK=off $(GOMOBILE) bind -target=$(ANDROID_TARGETS) -androidapi $(ANDROID_API) \
		-javapkg=$(ANDROID_PKG) -tags "$(ANDROID_TAGS)" \
		-ldflags="-checklinkname=0 -s -w $(VERSION_LDFLAGS)" \
		-o ../app/libs/ladderagent.aar ./mobile
	@echo "==> ladderagent.aar updated."

assemble:
	./gradlew assembleDebug

build: assemble

release:
	./gradlew assembleRelease

test:
	./gradlew test

clean:
	./gradlew clean
