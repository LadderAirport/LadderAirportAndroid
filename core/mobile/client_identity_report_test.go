//go:build client_version_report

package mobile

import (
	"testing"

	"github.com/ladderairport/agent/internal/version"
)

func TestPublishClientIdentityAssignsPackageVars(t *testing.T) {
	prevType, prevClient, prevCore := version.ClientType, version.ClientVersion, version.Version
	t.Cleanup(func() {
		version.ClientType = prevType
		version.ClientVersion = prevClient
		version.Version = prevCore
	})
	version.Version = "v0.15.9-3-g2ebfece"
	version.ClientType = "linux"
	version.ClientVersion = ""

	status := mustStatus(t, map[string]any{
		"panel_url":      "http://127.0.0.1:8080",
		"node_id":        "test-node",
		"token":          "test-token",
		"data_dir":       t.TempDir(),
		"client_version": "1.1.0",
		"client_type":    "linux",
	})
	if version.ClientType != ClientTypeAndroid {
		t.Fatalf("version.ClientType = %q, want android", version.ClientType)
	}
	if version.ClientVersion != "1.1.0" {
		t.Fatalf("version.ClientVersion = %q, want 1.1.0", version.ClientVersion)
	}
	if version.Version != "v0.15.9-3-g2ebfece" {
		t.Fatalf("version.Version = %q, core must stay unchanged", version.Version)
	}
	if status.AgentVersion != "v0.15.9-3-g2ebfece" || status.ClientVersion != "1.1.0" || status.ClientType != ClientTypeAndroid {
		t.Fatalf("status agent=%q client=%q type=%q", status.AgentVersion, status.ClientVersion, status.ClientType)
	}

	id := version.Report(version.Version)
	if id.AgentVersion != "v0.15.9-3-g2ebfece" || id.CoreVersion != id.AgentVersion {
		t.Fatalf("report core = %+v", id)
	}
	if id.ClientType != "android" || id.ClientVersion != "1.1.0" {
		t.Fatalf("report client = %+v", id)
	}
}

func TestPublishLegacyVersionKey(t *testing.T) {
	prevType, prevClient := version.ClientType, version.ClientVersion
	t.Cleanup(func() {
		version.ClientType = prevType
		version.ClientVersion = prevClient
	})
	version.ClientType = "linux"
	version.ClientVersion = "stale"

	_ = mustStatus(t, map[string]any{
		"panel_url": "http://127.0.0.1:8080",
		"node_id":   "test-node",
		"token":     "test-token",
		"data_dir":  t.TempDir(),
		"version":   "1.0.0",
	})
	if version.ClientType != "android" || version.ClientVersion != "1.0.0" {
		t.Fatalf("ClientType=%q ClientVersion=%q", version.ClientType, version.ClientVersion)
	}
}
