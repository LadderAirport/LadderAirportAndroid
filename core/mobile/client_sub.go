package mobile

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	clientSubUserAgent = "sing-box LadderAirportAndroid"
	clientCurrentFile  = "current.json"
	clientMetaFile     = "meta.json"
	defaultRefreshSecs = 21600
)

// ClientSubMeta is persisted alongside the cached sing-box config.
type ClientSubMeta struct {
	SubURL       string `json:"sub_url"`
	FetchedAt    int64  `json:"fetched_at_unix"`
	ContentHash  string `json:"content_hash,omitempty"`
	OutboundTags []string `json:"outbound_tags,omitempty"`
	SelectedTag  string `json:"selected_tag,omitempty"`
	Bytes        int    `json:"bytes"`
	Error        string `json:"error,omitempty"`
}

// NormalizeSubURL ensures flag=singbox is set (overriding conflicting format/flag).
func NormalizeSubURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", fmt.Errorf("订阅链接为空")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("订阅链接无效：%w", err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", fmt.Errorf("订阅链接须为 http 或 https")
	}
	if u.Host == "" {
		return "", fmt.Errorf("订阅链接缺少主机")
	}
	q := u.Query()
	q.Del("format")
	q.Del("target")
	q.Del("clash")
	q.Del("v2ray")
	q.Set("flag", "singbox")
	u.RawQuery = q.Encode()
	return u.String(), nil
}

func (r *ClientRunner) clientDir() string {
	return filepath.Join(r.cfg.DataDir, "client")
}

func (r *ClientRunner) currentPath() string {
	return filepath.Join(r.clientDir(), clientCurrentFile)
}

func (r *ClientRunner) metaPath() string {
	return filepath.Join(r.clientDir(), clientMetaFile)
}

// FetchSub downloads the Ladder subscription and caches the raw sing-box JSON.
func (r *ClientRunner) FetchSub() error {
	r.mu.Lock()
	subURL := r.cfg.SubURL
	r.mu.Unlock()

	normalized, err := NormalizeSubURL(subURL)
	if err != nil {
		return err
	}

	r.logf("拉取订阅：%s", normalized)

	ctxTimeout := 45 * time.Second
	client := &http.Client{Timeout: ctxTimeout}
	req, err := http.NewRequest(http.MethodGet, normalized, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", clientSubUserAgent)
	req.Header.Set("Accept", "application/json, text/plain, */*")

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("拉取订阅失败：%w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return fmt.Errorf("读取订阅正文失败：%w", err)
	}
	if resp.StatusCode != http.StatusOK {
		snippet := strings.TrimSpace(string(body))
		if len(snippet) > 200 {
			snippet = snippet[:200]
		}
		return fmt.Errorf("拉取订阅 HTTP %d：%s", resp.StatusCode, snippet)
	}
	if len(body) == 0 {
		return fmt.Errorf("订阅正文为空")
	}

	// Validate JSON object shape early.
	var probe map[string]any
	if err := json.Unmarshal(body, &probe); err != nil {
		return fmt.Errorf("订阅不是合法 sing-box JSON：%w", err)
	}
	if _, ok := probe["outbounds"]; !ok {
		return fmt.Errorf("订阅缺少 outbounds 字段")
	}

	tags := extractProxyOutboundTags(body)
	selected := ""
	if meta, _ := r.loadMeta(); meta != nil && meta.SelectedTag != "" {
		for _, t := range tags {
			if t == meta.SelectedTag {
				selected = meta.SelectedTag
				break
			}
		}
	}

	if err := os.MkdirAll(r.clientDir(), 0o755); err != nil {
		return fmt.Errorf("创建客户端目录失败：%w", err)
	}
	if err := os.WriteFile(r.currentPath(), body, 0o644); err != nil {
		return fmt.Errorf("写入订阅缓存失败：%w", err)
	}

	meta := &ClientSubMeta{
		SubURL:       normalized,
		FetchedAt:    time.Now().Unix(),
		OutboundTags: tags,
		SelectedTag:  selected,
		Bytes:        len(body),
	}
	if err := r.saveMeta(meta); err != nil {
		return err
	}

	r.mu.Lock()
	r.cfg.SubURL = normalized
	r.cachedRaw = body
	r.meta = meta
	r.mu.Unlock()

	r.logf("订阅已更新：%d 字节，%d 个节点", len(body), len(tags))
	return nil
}

func (r *ClientRunner) loadMeta() (*ClientSubMeta, error) {
	data, err := os.ReadFile(r.metaPath())
	if err != nil {
		return nil, err
	}
	var meta ClientSubMeta
	if err := json.Unmarshal(data, &meta); err != nil {
		return nil, err
	}
	return &meta, nil
}

func (r *ClientRunner) saveMeta(meta *ClientSubMeta) error {
	data, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(r.metaPath(), data, 0o644)
}

func (r *ClientRunner) loadCachedRaw() ([]byte, error) {
	r.mu.Lock()
	if len(r.cachedRaw) > 0 {
		out := append([]byte(nil), r.cachedRaw...)
		r.mu.Unlock()
		return out, nil
	}
	r.mu.Unlock()
	data, err := os.ReadFile(r.currentPath())
	if err != nil {
		return nil, fmt.Errorf("无本地订阅缓存，请先更新订阅：%w", err)
	}
	r.mu.Lock()
	r.cachedRaw = data
	r.mu.Unlock()
	return data, nil
}

func extractProxyOutboundTags(raw []byte) []string {
	var cfg struct {
		Outbounds []struct {
			Type string `json:"type"`
			Tag  string `json:"tag"`
		} `json:"outbounds"`
	}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return nil
	}
	skip := map[string]bool{
		"selector": true, "urltest": true, "direct": true, "block": true,
		"dns": true, "reject": true, "noop": true,
	}
	var tags []string
	for _, ob := range cfg.Outbounds {
		if ob.Tag == "" || ob.Tag == "proxy" {
			continue
		}
		if skip[ob.Type] {
			continue
		}
		tags = append(tags, ob.Tag)
	}
	return tags
}
