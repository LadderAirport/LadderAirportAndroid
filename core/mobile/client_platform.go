package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/process"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

// TunHost is implemented by Android VpnService to open TUN and protect sockets.
type TunHost interface {
	// OpenTun establishes the VPN tunnel; optionsJSON describes addresses/routes/DNS/MTU.
	// Returns the TUN file descriptor.
	OpenTun(optionsJSON string) (int32, error)
	// Protect excludes a socket from the VPN routing table.
	Protect(fd int32) error
}

type tunOpenRequest struct {
	MTU              int      `json:"mtu"`
	AutoRoute        bool     `json:"auto_route"`
	StrictRoute      bool     `json:"strict_route"`
	Inet4Address     []string `json:"inet4_address"`
	Inet6Address     []string `json:"inet6_address"`
	Inet4RouteRange  []string `json:"inet4_route_range"`
	Inet6RouteRange  []string `json:"inet6_route_range"`
	DNSServer        string   `json:"dns_server"`
	ExcludePackage   []string `json:"exclude_package,omitempty"`
	IncludePackage   []string `json:"include_package,omitempty"`
}

type clientPlatform struct {
	host    Host
	tunHost TunHost

	networkManager adapter.NetworkManager
	myTunName      string

	monitor *clientInterfaceMonitor
}

func newClientPlatform(host Host, tunHost TunHost) *clientPlatform {
	p := &clientPlatform{host: host, tunHost: tunHost}
	p.monitor = &clientInterfaceMonitor{platform: p}
	return p
}

var _ platform.Interface = (*clientPlatform)(nil)

func (p *clientPlatform) Initialize(networkManager adapter.NetworkManager) error {
	p.networkManager = networkManager
	return nil
}

func (p *clientPlatform) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (p *clientPlatform) AutoDetectInterfaceControl(fd int) error {
	if p.tunHost == nil {
		return fmt.Errorf("TunHost 未设置")
	}
	return p.tunHost.Protect(int32(fd))
}

func (p *clientPlatform) OpenTun(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if p.tunHost == nil {
		return nil, fmt.Errorf("TunHost 未设置")
	}
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, E.New("platform: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, E.New("platform: unsupported android_user option")
	}

	routeRanges, err := options.BuildAutoRouteRanges(true)
	if err != nil {
		return nil, err
	}

	req := tunOpenRequest{
		MTU:            int(options.MTU),
		AutoRoute:      options.AutoRoute,
		StrictRoute:    options.StrictRoute,
		Inet4Address:   prefixesToStrings(options.Inet4Address),
		Inet6Address:   prefixesToStrings(options.Inet6Address),
		Inet4RouteRange: filterPrefixStrings(routeRanges, true),
		Inet6RouteRange: filterPrefixStrings(routeRanges, false),
		ExcludePackage: options.ExcludePackage,
		IncludePackage: options.IncludePackage,
	}
	if len(options.Inet4Address) > 0 && options.Inet4Address[0].Bits() < 32 {
		req.DNSServer = options.Inet4Address[0].Addr().Next().String()
	}

	payload, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}

	tunFd, err := p.tunHost.OpenTun(string(payload))
	if err != nil {
		return nil, err
	}

	name, err := getTunnelName(tunFd)
	if err != nil || name == "" {
		name = "tun0"
	}
	options.Name = name
	if options.InterfaceMonitor != nil {
		options.InterfaceMonitor.RegisterMyInterface(name)
	}
	p.myTunName = name

	dupFd, err := syscall.Dup(int(tunFd))
	if err != nil {
		return nil, E.Cause(err, "dup tun file descriptor")
	}
	options.FileDescriptor = dupFd
	return tun.New(*options)
}

func (p *clientPlatform) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	p.monitor.logger = logger
	return p.monitor
}

func (p *clientPlatform) Interfaces() ([]adapter.NetworkInterface, error) {
	if p.host == nil {
		return nil, os.ErrInvalid
	}
	raw := p.host.InterfacesJSON()
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
		return nil, err
	}
	var out []adapter.NetworkInterface
	for _, item := range list {
		if item.Name == p.myTunName {
			continue
		}
		addrs := make([]netip.Prefix, 0, len(item.Addresses))
		for _, a := range item.Addresses {
			if prefix, err := netip.ParsePrefix(a); err == nil {
				addrs = append(addrs, prefix)
			} else if ip, err := netip.ParseAddr(a); err == nil {
				if ip.Is4() {
					addrs = append(addrs, netip.PrefixFrom(ip, 32))
				} else {
					addrs = append(addrs, netip.PrefixFrom(ip, 128))
				}
			}
		}
		flags := net.FlagUp
		if item.Loopback {
			flags |= net.FlagLoopback
		}
		out = append(out, adapter.NetworkInterface{
			Interface: control.Interface{
				Name:      item.Name,
				MTU:       int(item.Mtu),
				Addresses: addrs,
				Flags:     flags,
			},
		})
	}
	return out, nil
}

func (p *clientPlatform) UnderNetworkExtension() bool { return false }
func (p *clientPlatform) IncludeAllNetworks() bool    { return false }
func (p *clientPlatform) ClearDNSCache()              {}
func (p *clientPlatform) ReadWIFIState() adapter.WIFIState {
	return adapter.WIFIState{}
}
func (p *clientPlatform) SystemCertificates() []string { return nil }
func (p *clientPlatform) FindProcessInfo(ctx context.Context, network string, source netip.AddrPort, destination netip.AddrPort) (*process.Info, error) {
	return nil, os.ErrInvalid
}
func (p *clientPlatform) SendNotification(notification *platform.Notification) error {
	return nil
}

func prefixesToStrings(list []netip.Prefix) []string {
	return common.Map(list, func(p netip.Prefix) string { return p.String() })
}

func filterPrefixStrings(list []netip.Prefix, v4 bool) []string {
	var out []string
	for _, p := range list {
		if v4 && p.Addr().Is4() {
			out = append(out, p.String())
		}
		if !v4 && p.Addr().Is6() {
			out = append(out, p.String())
		}
	}
	return out
}

// clientInterfaceMonitor is a minimal DefaultInterfaceMonitor for Android VPN.
type clientInterfaceMonitor struct {
	platform *clientPlatform
	logger   logger.Logger

	access          sync.Mutex
	defaultIface    *control.Interface
	callbacks       list.List[tun.DefaultInterfaceUpdateCallback]
	myInterface     string
}

var _ tun.DefaultInterfaceMonitor = (*clientInterfaceMonitor)(nil)

func (m *clientInterfaceMonitor) Start() error {
	ifaces, err := m.platform.Interfaces()
	if err != nil {
		if m.logger != nil {
			m.logger.Warn("list interfaces: ", err)
		}
		return nil
	}
	var chosen *control.Interface
	for i := range ifaces {
		iface := ifaces[i].Interface
		if iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if len(iface.Addresses) == 0 {
			continue
		}
		chosen = &ifaces[i].Interface
		break
	}
	m.access.Lock()
	m.defaultIface = chosen
	callbacks := m.callbacks.Array()
	m.access.Unlock()
	for _, cb := range callbacks {
		cb(chosen, 0)
	}
	return nil
}

func (m *clientInterfaceMonitor) Close() error { return nil }

func (m *clientInterfaceMonitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.defaultIface
}

func (m *clientInterfaceMonitor) OverrideAndroidVPN() bool { return false }
func (m *clientInterfaceMonitor) AndroidVPNEnabled() bool  { return false }

func (m *clientInterfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *clientInterfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *clientInterfaceMonitor) RegisterMyInterface(interfaceName string) {
	m.access.Lock()
	defer m.access.Unlock()
	m.myInterface = interfaceName
}

func (m *clientInterfaceMonitor) MyInterface() string {
	m.access.Lock()
	defer m.access.Unlock()
	return m.myInterface
}
