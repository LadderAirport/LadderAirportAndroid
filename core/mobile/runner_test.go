package mobile

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/ladderairport/agent/internal/version"
)

type mockHost struct {
	logs       []string
	metrics    string
	interfaces string
}

func (m *mockHost) WriteLog(line string) {
	m.logs = append(m.logs, line)
}

func (m *mockHost) NodeMetricsJSON() string {
	return m.metrics
}

func (m *mockHost) InterfacesJSON() string {
	return m.interfaces
}

func TestNewRunner(t *testing.T) {
	tmpDir := t.TempDir()
	cfg := Config{
		PanelURL: "http://127.0.0.1:8080",
		NodeID:   "test-node",
		Token:    "test-token",
		DataDir:  tmpDir,
	}
	raw, err := json.Marshal(cfg)
	if err != nil {
		t.Fatalf("marshal config: %v", err)
	}

	host := &mockHost{
		metrics:    `{"cpu_percent": 15.0}`,
		interfaces: `[{"name":"wlan0","up":true}]`,
	}

	r, err := NewRunner(string(raw), host)
	if err != nil {
		t.Fatalf("NewRunner failed: %v", err)
	}
	if r.IsRunning() {
		t.Errorf("expected runner not running initially")
	}

	status := r.StatusJSON()
	var st StatusInfo
	if err := json.Unmarshal([]byte(status), &st); err != nil {
		t.Fatalf("unmarshal status JSON: %v", err)
	}
	if st.Running {
		t.Errorf("expected running=false")
	}
	if st.NodeID != "test-node" {
		t.Errorf("expected node_id=test-node, got %s", st.NodeID)
	}
}

func TestClientVersionDoesNotOverrideCoreVersion(t *testing.T) {
	withCoreIdentity(t, "v0.15.9-3-g2ebfece", "2ebfece")
	status := mustStatus(t, map[string]any{
		"panel_url":      "http://127.0.0.1:8080",
		"node_id":        "test-node",
		"token":          "test-token",
		"data_dir":       t.TempDir(),
		"client_version": "1.1.0",
		"client_type":    "android",
		// Legacy key used to replace agent_version. A different shell string
		// must not win over client_version or become the core version.
		"version": "9.9.9",
	})
	if status.AgentVersion != "v0.15.9-3-g2ebfece" || status.CoreVersion != status.AgentVersion {
		t.Fatalf("core/agent version = %q / %q, want v0.15.9-3-g2ebfece", status.AgentVersion, status.CoreVersion)
	}
	if status.CoreCommit != "2ebfece" {
		t.Fatalf("core_commit = %q", status.CoreCommit)
	}
	if status.ClientVersion != "1.1.0" {
		t.Fatalf("client_version = %q, want 1.1.0", status.ClientVersion)
	}
	if status.ClientType != ClientTypeAndroid {
		t.Fatalf("client_type = %q, want android", status.ClientType)
	}
	if status.ClientVersion == status.AgentVersion {
		t.Fatal("client_version must stay distinct from the core version")
	}
}

func TestLegacyVersionKeyIsClientVersionOnly(t *testing.T) {
	withCoreIdentity(t, "v0.15.12", "abc1234")
	status := mustStatus(t, map[string]any{
		"panel_url": "http://127.0.0.1:8080",
		"node_id":   "test-node",
		"token":     "test-token",
		"data_dir":  t.TempDir(),
		"version":   "1.0.0",
	})
	if status.AgentVersion != "v0.15.12" || status.CoreVersion != "v0.15.12" {
		t.Fatalf("legacy version key overwrote core: agent=%q core=%q", status.AgentVersion, status.CoreVersion)
	}
	if status.ClientVersion != "1.0.0" {
		t.Fatalf("client_version = %q, want legacy version 1.0.0", status.ClientVersion)
	}
	if status.ClientType != ClientTypeAndroid {
		t.Fatalf("client_type = %q", status.ClientType)
	}
}

func TestClientTypeIsAlwaysAndroid(t *testing.T) {
	withCoreIdentity(t, "v0.15.12", "abc1234")
	status := mustStatus(t, map[string]any{
		"panel_url":      "http://127.0.0.1:8080",
		"node_id":        "test-node",
		"token":          "test-token",
		"data_dir":       t.TempDir(),
		"client_version": "1.1.0",
		"client_type":    "linux",
	})
	if status.ClientType != ClientTypeAndroid {
		t.Fatalf("client_type = %q, want android", status.ClientType)
	}
	if status.ClientVersion != "1.1.0" || status.AgentVersion != "v0.15.12" {
		t.Fatalf("identity drifted: client=%q agent=%q", status.ClientVersion, status.AgentVersion)
	}
}

func TestOmittedClientVersionStaysEmpty(t *testing.T) {
	withCoreIdentity(t, "v0.15.12", "abc1234")
	status := mustStatus(t, map[string]any{
		"panel_url": "http://127.0.0.1:8080",
		"node_id":   "test-node",
		"token":     "test-token",
		"data_dir":  t.TempDir(),
	})
	if status.ClientVersion != "" {
		t.Fatalf("client_version = %q, want empty rather than the core version", status.ClientVersion)
	}
	if status.AgentVersion != "v0.15.12" || status.ClientType != ClientTypeAndroid {
		t.Fatalf("agent=%q client_type=%q", status.AgentVersion, status.ClientType)
	}
}

func withCoreIdentity(t *testing.T, ver, commit string) {
	t.Helper()
	prevVer, prevCommit := version.Version, version.Commit
	version.Version = ver
	version.Commit = commit
	t.Cleanup(func() {
		version.Version = prevVer
		version.Commit = prevCommit
	})
}

func mustStatus(t *testing.T, cfg map[string]any) StatusInfo {
	t.Helper()
	raw, err := json.Marshal(cfg)
	if err != nil {
		t.Fatalf("marshal config: %v", err)
	}
	r, err := NewRunner(string(raw), &mockHost{})
	if err != nil {
		t.Fatalf("NewRunner: %v", err)
	}
	var status StatusInfo
	if err := json.Unmarshal([]byte(r.StatusJSON()), &status); err != nil {
		t.Fatalf("unmarshal status: %v", err)
	}
	return status
}

func TestRunnerHostLogging(t *testing.T) {
	host := &mockHost{}
	w := &hostLogWriter{host: host}
	_, _ = w.Write([]byte("hello world\nline 2\n"))
	if len(host.logs) != 2 {
		t.Fatalf("expected 2 log lines, got %d", len(host.logs))
	}
	if host.logs[0] != "hello world" || host.logs[1] != "line 2" {
		t.Errorf("unexpected logs: %v", host.logs)
	}
}

func TestEnrollmentFlow(t *testing.T) {
	tmpDir := t.TempDir()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/enroll" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") != "Bearer test-enroll-token" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"control_token": "issued-control-token",
		})
	}))
	defer srv.Close()

	enrollCfg := EnrollConfig{
		PanelURL:    srv.URL,
		NodeID:      "test-node",
		EnrollToken: "test-enroll-token",
		DataDir:     tmpDir,
	}
	raw, _ := json.Marshal(enrollCfg)

	resJSON, err := Enroll(string(raw))
	if err != nil {
		t.Fatalf("Enroll failed: %v", err)
	}

	var res EnrollResult
	if err := json.Unmarshal([]byte(resJSON), &res); err != nil {
		t.Fatalf("unmarshal EnrollResult: %v", err)
	}
	if !res.OK {
		t.Errorf("expected ok=true, got error=%s", res.Error)
	}
	if res.Token != "issued-control-token" {
		t.Errorf("expected token=issued-control-token, got %s", res.Token)
	}
	if fileExists(filepath.Join(tmpDir, "agent.key")) || fileExists(filepath.Join(tmpDir, "agent.crt")) || fileExists(filepath.Join(tmpDir, "ca.crt")) {
		t.Errorf("uplink enrollment must not write management TLS files")
	}
}
