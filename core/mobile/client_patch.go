package mobile

import (
	"encoding/json"
	"fmt"
)

const (
	defaultTunIPv4 = "172.19.0.1/30"
	defaultTunIPv6 = "fdfe:dcba:9876::1/126"
	defaultTunMTU  = 9000
)

// PatchForTUN rewrites Ladder sub JSON: replace mixed inbound with tun for VpnService.
// Keeps outbounds / dns / route / rule_set. Ensures auto_detect_interface.
func PatchForTUN(raw []byte, selectedTag string) ([]byte, error) {
	var root map[string]any
	if err := json.Unmarshal(raw, &root); err != nil {
		return nil, fmt.Errorf("解析订阅 JSON 失败：%w", err)
	}

	root["inbounds"] = []any{
		map[string]any{
			"type":        "tun",
			"tag":         "tun-in",
			"address":     []any{defaultTunIPv4, defaultTunIPv6},
			"mtu":         defaultTunMTU,
			"auto_route":  true,
			"strict_route": true,
			"stack":       "mixed",
			"sniff":       true,
			"sniff_override_destination": true,
		},
	}

	if route, ok := root["route"].(map[string]any); ok {
		route["auto_detect_interface"] = true
		root["route"] = route
	} else {
		root["route"] = map[string]any{
			"auto_detect_interface": true,
			"final":                 "proxy",
		}
	}

	if selectedTag != "" {
		if err := setSelectorDefault(root, selectedTag); err != nil {
			return nil, err
		}
	}

	out, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return nil, err
	}
	return out, nil
}

func setSelectorDefault(root map[string]any, tag string) error {
	outbounds, ok := root["outbounds"].([]any)
	if !ok {
		return fmt.Errorf("outbounds 格式无效")
	}
	found := false
	for _, item := range outbounds {
		ob, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if ob["type"] == "selector" && ob["tag"] == "proxy" {
			ob["default"] = tag
			found = true
			break
		}
	}
	if !found {
		return fmt.Errorf("未找到 tag=proxy 的 selector")
	}
	// Ensure selected tag exists among outbounds.
	exists := false
	for _, item := range outbounds {
		ob, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if ob["tag"] == tag {
			exists = true
			break
		}
	}
	if !exists {
		return fmt.Errorf("节点 %q 不存在", tag)
	}
	return nil
}

// ApplySelectedTagToRaw sets selector default on cached raw JSON without TUN patch.
func ApplySelectedTagToRaw(raw []byte, selectedTag string) ([]byte, error) {
	if selectedTag == "" {
		return raw, nil
	}
	var root map[string]any
	if err := json.Unmarshal(raw, &root); err != nil {
		return nil, err
	}
	if err := setSelectorDefault(root, selectedTag); err != nil {
		return nil, err
	}
	return json.MarshalIndent(root, "", "  ")
}
