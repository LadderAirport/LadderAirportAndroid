package mobile

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestNormalizeSubURL(t *testing.T) {
	got, err := NormalizeSubURL("https://panel.example/sub/tok123")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(got, "flag=singbox") {
		t.Fatalf("expected flag=singbox in %s", got)
	}

	got, err = NormalizeSubURL("https://panel.example/sub/tok?flag=clash&format=v2ray")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(got, "clash") || strings.Contains(got, "v2ray") {
		t.Fatalf("expected clash/v2ray removed: %s", got)
	}
	if !strings.Contains(got, "flag=singbox") {
		t.Fatalf("expected flag=singbox: %s", got)
	}
}

func TestPatchForTUN(t *testing.T) {
	raw := []byte(`{
  "inbounds": [{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2080}],
  "outbounds": [
    {"type":"selector","tag":"proxy","outbounds":["n1","direct"]},
    {"type":"shadowsocks","tag":"n1","server":"1.2.3.4","server_port":8388},
    {"type":"direct","tag":"direct"}
  ],
  "route": {"final":"proxy","auto_detect_interface":true}
}`)
	out, err := PatchForTUN(raw, "n1")
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	if err := json.Unmarshal(out, &root); err != nil {
		t.Fatal(err)
	}
	inbounds := root["inbounds"].([]any)
	in0 := inbounds[0].(map[string]any)
	if in0["type"] != "tun" {
		t.Fatalf("expected tun inbound, got %v", in0["type"])
	}
	found := false
	for _, item := range root["outbounds"].([]any) {
		ob := item.(map[string]any)
		if ob["tag"] == "proxy" {
			if ob["default"] != "n1" {
				t.Fatalf("expected default n1, got %v", ob["default"])
			}
			found = true
		}
	}
	if !found {
		t.Fatal("selector not found")
	}
}

func TestExtractProxyOutboundTags(t *testing.T) {
	raw := []byte(`{
  "outbounds": [
    {"type":"selector","tag":"proxy","outbounds":["a","direct"]},
    {"type":"trojan","tag":"a"},
    {"type":"direct","tag":"direct"},
    {"type":"dns","tag":"dns-out"}
  ]
}`)
	tags := extractProxyOutboundTags(raw)
	if len(tags) != 1 || tags[0] != "a" {
		t.Fatalf("unexpected tags: %v", tags)
	}
}
