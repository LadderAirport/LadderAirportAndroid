package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/ladderairport/agent/internal/control"
	"github.com/ladderairport/agent/internal/managementpki"
	"github.com/ladderairport/agent/internal/panelhttp"
	"github.com/ladderairport/agent/internal/protocolcert"
	"github.com/ladderairport/agent/internal/uplink"
	"github.com/ladderairport/agent/internal/uplinkws"
	"github.com/ladderairport/agent/internal/version"
	agentv1 "github.com/ladderairport/proto/gen/go/agent/v1"
	_ "github.com/sagernet/gomobile/bind"
)

// Host is the gobind-compatible callback interface implemented by Android Kotlin.
type Host interface {
	WriteLog(line string)
	NodeMetricsJSON() string
	InterfacesJSON() string
}

// Config is the configuration for Runner passed as JSON from Kotlin.
type Config struct {
	PanelURL   string `json:"panel_url"`
	NodeID     string `json:"node_id"`
	Token      string `json:"token"`
	DataDir    string `json:"data_dir"`
	TLSCert    string `json:"tls_cert"`
	TLSKey     string `json:"tls_key"`
	TLSCA      string `json:"tls_ca"`
	UplinkWS   bool   `json:"uplink_ws"`
	ReportSecs int    `json:"report_secs"`
	ConfigSecs int    `json:"config_secs"`
	Version    string `json:"version,omitempty"`
}

// Runner drives the agent lifecycle on Android (uplink-ws + in-process sing-box).
type Runner struct {
	mu        sync.Mutex
	cfg       Config
	host      Host
	running   bool
	cancel    context.CancelFunc
	rt        *control.BoxRuntime
	srv       *control.Server
	startedAt int64
	lastError string
	logWriter *hostLogWriter
}

type hostLogWriter struct {
	mu   sync.Mutex
	host Host
}

func (w *hostLogWriter) Write(p []byte) (n int, err error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.host != nil {
		lines := strings.Split(string(p), "\n")
		for _, line := range lines {
			line = strings.TrimRight(line, "\r")
			if line != "" {
				w.host.WriteLog(line)
			}
		}
	}
	return len(p), nil
}

// NewRunner creates a new Runner instance.
func NewRunner(cfgJSON string, host Host) (*Runner, error) {
	var cfg Config
	cfg.UplinkWS = true
	cfg.ReportSecs = 15
	cfg.ConfigSecs = 60

	if strings.TrimSpace(cfgJSON) != "" {
		if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
			return nil, fmt.Errorf("解析配置 JSON 失败：%w", err)
		}
	}
	if strings.TrimSpace(cfg.DataDir) == "" {
		return nil, fmt.Errorf("缺少必须的 data_dir 参数")
	}
	if cfg.ReportSecs <= 0 {
		cfg.ReportSecs = 15
	}
	if cfg.ConfigSecs <= 0 {
		cfg.ConfigSecs = 60
	}
	if strings.TrimSpace(cfg.Version) == "" {
		cfg.Version = version.Version
	}

	return &Runner{
		cfg:       cfg,
		host:      host,
		logWriter: &hostLogWriter{host: host},
	}, nil
}

// New is an alias for NewRunner for compatibility with android-agent.md.
func New(cfgJSON string, host Host) (*Runner, error) {
	return NewRunner(cfgJSON, host)
}

// Start boots control.Server, uplink HTTP and uplink WebSocket.
// Android nodes do not initialize management TLS.
func (r *Runner) Start() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.running {
		return nil
	}

	if strings.TrimSpace(r.cfg.PanelURL) == "" || strings.TrimSpace(r.cfg.NodeID) == "" || strings.TrimSpace(r.cfg.Token) == "" {
		return fmt.Errorf("必须提供 panel_url、node_id 与 token")
	}
	if err := managementpki.ParsePanelURL(r.cfg.PanelURL); err != nil {
		return err
	}
	if err := os.MkdirAll(r.cfg.DataDir, 0o755); err != nil {
		return fmt.Errorf("创建数据目录失败：%w", err)
	}

	rt := control.NewBoxRuntime(r.cfg.DataDir)
	logs := control.NewLogBuf(0)
	log.SetOutput(io.MultiWriter(os.Stderr, logs.Writer("info"), r.logWriter))

	singboxVer := control.SingboxVersion()
	agentVer := r.cfg.Version
	srv := control.NewServer(rt, agentVer, singboxVer, logs)
	srv.SetDataDir(r.cfg.DataDir)
	resolver := control.NewPublicAddressResolver()
	srv.SetPublicAddressResolver(resolver)

	if r.host != nil {
		srv.SetInterfacesProvider(func() ([]*agentv1.NetworkInterface, error) {
			raw := r.host.InterfacesJSON()
			if strings.TrimSpace(raw) == "" {
				return nil, fmt.Errorf("host interfacesJSON 为空")
			}
			var list []struct {
				Name         string   `json:"name"`
				Up           bool     `json:"up"`
				Loopback     bool     `json:"loopback"`
				Mtu          int32    `json:"mtu"`
				HardwareAddr string   `json:"hardware_addr"`
				Addresses    []string `json:"addresses"`
			}
			if err := json.Unmarshal([]byte(raw), &list); err != nil {
				return nil, fmt.Errorf("解析 host interfacesJSON 失败: %w", err)
			}
			out := make([]*agentv1.NetworkInterface, 0, len(list))
			for _, item := range list {
				out = append(out, &agentv1.NetworkInterface{
					Name:         item.Name,
					Up:           item.Up,
					Loopback:     item.Loopback,
					Mtu:          item.Mtu,
					HardwareAddr: item.HardwareAddr,
					Addresses:    item.Addresses,
				})
			}
			return out, nil
		})

		srv.SetNodeMetricsProvider(func() (*agentv1.GetNodeMetricsResponse, error) {
			raw := r.host.NodeMetricsJSON()
			if strings.TrimSpace(raw) == "" {
				return nil, fmt.Errorf("host nodeMetricsJSON 为空")
			}
			var m struct {
				CpuPercent       float64 `json:"cpu_percent"`
				MemoryTotalBytes uint64  `json:"memory_total_bytes"`
				MemoryUsedBytes  uint64  `json:"memory_used_bytes"`
				DiskTotalBytes   uint64  `json:"disk_total_bytes"`
				DiskUsedBytes    uint64  `json:"disk_used_bytes"`
				DownlinkBps      uint64  `json:"downlink_bps"`
				UplinkBps        uint64  `json:"uplink_bps"`
				CollectedAtUnix  int64   `json:"collected_at_unix"`
			}
			if err := json.Unmarshal([]byte(raw), &m); err != nil {
				return nil, fmt.Errorf("解析 host nodeMetricsJSON 失败: %w", err)
			}
			return &agentv1.GetNodeMetricsResponse{
				CpuPercent:       m.CpuPercent,
				MemoryTotalBytes: m.MemoryTotalBytes,
				MemoryUsedBytes:  m.MemoryUsedBytes,
				DiskTotalBytes:   m.DiskTotalBytes,
				DiskUsedBytes:    m.DiskUsedBytes,
				UplinkBps:        m.UplinkBps,
				DownlinkBps:      m.DownlinkBps,
				CollectedAtUnix:  m.CollectedAtUnix,
			}, nil
		})
	}

	protocolCerts, err := protocolcert.New(filepath.Join(r.cfg.DataDir, "protocol-certs"))
	if err == nil {
		srv.SetProtocolCertificateManager(protocolCerts)
	}

	panelHTTP := panelhttp.NewClient()

	ctx, cancel := context.WithCancel(context.Background())
	r.cancel = cancel
	r.rt = rt
	r.srv = srv
	r.startedAt = time.Now().Unix()
	r.running = true
	r.lastError = ""

	uplinkClient, err := uplink.New(uplink.Config{
		PanelURL:    r.cfg.PanelURL,
		NodeID:      r.cfg.NodeID,
		Token:       r.cfg.Token,
		ReportEvery: time.Duration(r.cfg.ReportSecs) * time.Second,
		ConfigEvery: time.Duration(r.cfg.ConfigSecs) * time.Second,
		HTTPClient:  panelHTTP,
		Control:     srv,
	})
	if err == nil {
		go uplinkClient.Run(ctx)
	} else {
		log.Printf("初始化 HTTP uplink 失败：%v", err)
	}

	if r.cfg.UplinkWS {
		wsClient, err := uplinkws.New(uplinkws.Config{
			PanelURL:    r.cfg.PanelURL,
			NodeID:      r.cfg.NodeID,
			Token:       r.cfg.Token,
			ReportEvery: time.Duration(r.cfg.ReportSecs) * time.Second,
			HTTPClient:  panelHTTP,
			Server:      srv,
		})
		if err == nil {
			go wsClient.Run(ctx)
			log.Printf("Android Agent: uplink-ws 客户端已启动")
		} else {
			log.Printf("初始化 WS uplink 失败：%v", err)
		}
	}

	log.Printf("Android Agent 已启动：NodeID=%s PanelURL=%s", r.cfg.NodeID, r.cfg.PanelURL)
	return nil
}

// Stop shuts down the agent background routines and sing-box runtime.
func (r *Runner) Stop() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.running {
		return nil
	}
	if r.cancel != nil {
		r.cancel()
		r.cancel = nil
	}
	if r.rt != nil {
		_ = r.rt.Stop(context.Background())
		r.rt = nil
	}
	r.running = false
	r.srv = nil
	log.Printf("Android Agent 已停止")
	return nil
}

// IsRunning reports whether the agent is currently active.
func (r *Runner) IsRunning() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running
}

// StatusInfo encapsulates real-time agent status.
type StatusInfo struct {
	Running        bool   `json:"running"`
	State          string `json:"state"`
	AgentVersion   string `json:"agent_version"`
	SingboxVersion string `json:"singbox_version"`
	PanelURL       string `json:"panel_url"`
	NodeID         string `json:"node_id"`
	StartedAtUnix  int64  `json:"started_at_unix"`
	UptimeSecs     int64  `json:"uptime_secs"`
	UplinkBytes    int64  `json:"uplink_bytes"`
	DownlinkBytes  int64  `json:"downlink_bytes"`
	Connections    int64  `json:"connections"`
	ConfigHash     string `json:"config_hash"`
	LastError      string `json:"last_error"`
}

// StatusJSON returns a JSON serialization of the agent status.
func (r *Runner) StatusJSON() string {
	r.mu.Lock()
	defer r.mu.Unlock()

	info := StatusInfo{
		Running:        r.running,
		State:          "stopped",
		AgentVersion:   r.cfg.Version,
		SingboxVersion: control.SingboxVersion(),
		PanelURL:       r.cfg.PanelURL,
		NodeID:         r.cfg.NodeID,
		StartedAtUnix:  r.startedAt,
		LastError:      r.lastError,
	}

	if r.running && r.rt != nil {
		ctx := context.Background()
		st := r.rt.Status(ctx)
		m := r.rt.Metrics(ctx)
		info.State = string(st.State)
		info.ConfigHash = st.ConfigHash
		if st.LastError != "" {
			info.LastError = st.LastError
		}
		info.UplinkBytes = m.UplinkBytes
		info.DownlinkBytes = m.DownlinkBytes
		info.Connections = m.Connections
		if r.startedAt > 0 {
			info.UptimeSecs = time.Now().Unix() - r.startedAt
		}
	}

	data, _ := json.Marshal(info)
	return string(data)
}

func fileExists(path string) bool {
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}
