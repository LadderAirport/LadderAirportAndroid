package mobile

import (
	"context"
	stdjson "encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/ladderairport/agent/internal/control"
	"github.com/ladderairport/agent/internal/version"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

// ClientConfig is passed as JSON from Kotlin.
type ClientConfig struct {
	SubURL      string `json:"sub_url"`
	DataDir     string `json:"data_dir"`
	RefreshSecs int    `json:"refresh_secs"`
	Version     string `json:"version,omitempty"`
}

// ClientRunner runs a local sing-box VPN client consuming a Ladder subscription.
type ClientRunner struct {
	mu        sync.Mutex
	cfg       ClientConfig
	host      Host
	tunHost   TunHost
	platform  *clientPlatform
	running   bool
	cancel    context.CancelFunc
	instance  *box.Box
	startedAt int64
	lastError string
	cachedRaw []byte
	meta      *ClientSubMeta
	logWriter *hostLogWriter
}

// NewClientRunner creates a ClientRunner.
func NewClientRunner(cfgJSON string, host Host, tunHost TunHost) (*ClientRunner, error) {
	var cfg ClientConfig
	cfg.RefreshSecs = defaultRefreshSecs
	if strings.TrimSpace(cfgJSON) != "" {
		if err := stdjson.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
			return nil, fmt.Errorf("解析客户端配置失败：%w", err)
		}
	}
	if strings.TrimSpace(cfg.DataDir) == "" {
		return nil, fmt.Errorf("缺少必须的 data_dir 参数")
	}
	if cfg.RefreshSecs <= 0 {
		cfg.RefreshSecs = defaultRefreshSecs
	}
	if strings.TrimSpace(cfg.Version) == "" {
		cfg.Version = version.Version
	}
	if normalized, err := NormalizeSubURL(cfg.SubURL); err == nil {
		cfg.SubURL = normalized
	}

	r := &ClientRunner{
		cfg:       cfg,
		host:      host,
		tunHost:   tunHost,
		logWriter: &hostLogWriter{host: host},
	}
	r.platform = newClientPlatform(host, tunHost)
	if meta, err := r.loadMeta(); err == nil {
		r.meta = meta
	}
	if data, err := os.ReadFile(r.currentPath()); err == nil {
		r.cachedRaw = data
	}
	return r, nil
}

func (r *ClientRunner) logf(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	log.Print(msg)
	if r.logWriter != nil && r.host != nil {
		_, _ = r.logWriter.Write([]byte(msg))
	}
}

// SetSubURL updates the subscription URL (persisted by the Android prefs layer).
func (r *ClientRunner) SetSubURL(subURL string) error {
	normalized, err := NormalizeSubURL(subURL)
	if err != nil {
		return err
	}
	r.mu.Lock()
	r.cfg.SubURL = normalized
	r.mu.Unlock()
	return nil
}

// Start launches sing-box with the cached (TUN-patched) subscription config.
func (r *ClientRunner) Start() error {
	r.mu.Lock()
	if r.running {
		r.mu.Unlock()
		return nil
	}
	r.mu.Unlock()

	if r.tunHost == nil {
		return fmt.Errorf("TunHost 未设置")
	}
	if err := os.MkdirAll(r.clientDir(), 0o755); err != nil {
		return err
	}

	raw, err := r.ensureFreshCache()
	if err != nil {
		r.setLastError(err.Error())
		return err
	}

	selected := ""
	if meta, err := r.loadMeta(); err == nil && meta != nil {
		selected = meta.SelectedTag
	}

	patched, err := PatchForTUN(raw, selected)
	if err != nil {
		r.setLastError(err.Error())
		return err
	}

	if err := r.startBox(patched); err != nil {
		r.setLastError(err.Error())
		return err
	}
	return nil
}

func (r *ClientRunner) ensureFreshCache() ([]byte, error) {
	raw, err := r.loadCachedRaw()
	needFetch := err != nil
	if !needFetch {
		meta, metaErr := r.loadMeta()
		if metaErr != nil || meta == nil || meta.FetchedAt == 0 {
			needFetch = true
		} else {
			r.mu.Lock()
			refresh := r.cfg.RefreshSecs
			r.mu.Unlock()
			if time.Now().Unix()-meta.FetchedAt > int64(refresh) {
				needFetch = true
			}
		}
	}
	if needFetch {
		if fetchErr := r.FetchSub(); fetchErr != nil {
			if raw != nil {
				r.logf("订阅刷新失败，使用本地缓存：%v", fetchErr)
				return raw, nil
			}
			return nil, fetchErr
		}
		return r.loadCachedRaw()
	}
	return raw, nil
}

func (r *ClientRunner) startBox(configJSON []byte) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.running {
		return nil
	}

	ctx := include.Context(context.Background())
	ctx = service.ContextWith[platform.Interface](ctx, r.platform)
	ctx, cancel := context.WithCancel(ctx)

	opts, err := json.UnmarshalExtendedContext[option.Options](ctx, configJSON)
	if err != nil {
		cancel()
		return fmt.Errorf("解析 TUN 配置失败：%w", err)
	}

	instance, err := box.New(box.Options{
		Context: ctx,
		Options: opts,
	})
	if err != nil {
		cancel()
		return fmt.Errorf("创建 sing-box 失败：%w", err)
	}

	done := make(chan error, 1)
	go func() {
		done <- instance.Start()
	}()
	if err := <-done; err != nil {
		_ = instance.Close()
		cancel()
		return fmt.Errorf("启动 sing-box 失败：%w", err)
	}

	_ = os.WriteFile(filepath.Join(r.clientDir(), "runtime.json"), configJSON, 0o644)

	r.instance = instance
	r.cancel = cancel
	r.running = true
	r.startedAt = time.Now().Unix()
	r.lastError = ""
	r.logf("客户端 VPN 已启动")
	return nil
}

// Stop shuts down the client sing-box instance.
func (r *ClientRunner) Stop() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.running {
		return nil
	}
	if r.cancel != nil {
		r.cancel()
		r.cancel = nil
	}
	if r.instance != nil {
		_ = r.instance.Close()
		r.instance = nil
	}
	r.running = false
	r.logf("客户端 VPN 已停止")
	return nil
}

// IsRunning reports whether the client is active.
func (r *ClientRunner) IsRunning() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running
}

// SelectOutbound updates the selector default and restarts if running.
func (r *ClientRunner) SelectOutbound(tag string) error {
	tag = strings.TrimSpace(tag)
	if tag == "" {
		return fmt.Errorf("节点 tag 为空")
	}
	raw, err := r.loadCachedRaw()
	if err != nil {
		return err
	}
	updated, err := ApplySelectedTagToRaw(raw, tag)
	if err != nil {
		return err
	}
	if err := os.WriteFile(r.currentPath(), updated, 0o644); err != nil {
		return err
	}
	meta, _ := r.loadMeta()
	if meta == nil {
		meta = &ClientSubMeta{}
	}
	meta.SelectedTag = tag
	meta.OutboundTags = extractProxyOutboundTags(updated)
	_ = r.saveMeta(meta)

	r.mu.Lock()
	r.cachedRaw = updated
	r.meta = meta
	wasRunning := r.running
	r.mu.Unlock()

	if wasRunning {
		_ = r.Stop()
		return r.Start()
	}
	return nil
}

// OutboundsJSON returns available proxy outbound tags and selection.
func (r *ClientRunner) OutboundsJSON() string {
	meta, err := r.loadMeta()
	if err != nil || meta == nil {
		raw, loadErr := r.loadCachedRaw()
		if loadErr != nil {
			data, _ := stdjson.Marshal(map[string]any{
				"tags":     []string{},
				"selected": "",
				"error":    loadErr.Error(),
			})
			return string(data)
		}
		tags := extractProxyOutboundTags(raw)
		data, _ := stdjson.Marshal(map[string]any{
			"tags":     tags,
			"selected": "",
		})
		return string(data)
	}
	data, _ := stdjson.Marshal(map[string]any{
		"tags":       meta.OutboundTags,
		"selected":   meta.SelectedTag,
		"fetched_at": meta.FetchedAt,
		"sub_url":    meta.SubURL,
		"bytes":      meta.Bytes,
	})
	return string(data)
}

// ClientStatusInfo is returned by StatusJSON.
type ClientStatusInfo struct {
	Running        bool   `json:"running"`
	State          string `json:"state"`
	SubURL         string `json:"sub_url"`
	SelectedTag    string `json:"selected_tag"`
	FetchedAtUnix  int64  `json:"fetched_at_unix"`
	StartedAtUnix  int64  `json:"started_at_unix"`
	UptimeSecs     int64  `json:"uptime_secs"`
	SingboxVersion string `json:"singbox_version"`
	LastError      string `json:"last_error"`
	OutboundCount  int    `json:"outbound_count"`
}

// StatusJSON returns client status.
func (r *ClientRunner) StatusJSON() string {
	r.mu.Lock()
	defer r.mu.Unlock()

	info := ClientStatusInfo{
		Running:        r.running,
		State:          "stopped",
		SubURL:         r.cfg.SubURL,
		SingboxVersion: control.SingboxVersion(),
		StartedAtUnix:  r.startedAt,
		LastError:      r.lastError,
	}
	if r.running {
		info.State = "running"
		if r.startedAt > 0 {
			info.UptimeSecs = time.Now().Unix() - r.startedAt
		}
	}
	if r.meta != nil {
		info.SelectedTag = r.meta.SelectedTag
		info.FetchedAtUnix = r.meta.FetchedAt
		info.OutboundCount = len(r.meta.OutboundTags)
		if info.SubURL == "" {
			info.SubURL = r.meta.SubURL
		}
	}
	data, _ := stdjson.Marshal(info)
	return string(data)
}

func (r *ClientRunner) setLastError(msg string) {
	r.mu.Lock()
	r.lastError = msg
	r.mu.Unlock()
}
