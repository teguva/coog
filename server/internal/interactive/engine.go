package interactive

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Engine hosts the intiface-engine child process, or attaches to one
// already listening (host systemd on Codu / Docker bridge gateway).
type Engine struct {
	bin  string
	host string
	port int
	udcf string
	name string

	mu        sync.Mutex
	cmd       *exec.Cmd
	external  bool
	onLogLine func(string)
}

func NewEngine(bin, host string, port int, udcf, serverName string) *Engine {
	if serverName == "" {
		serverName = "coog-engine"
	}
	if strings.TrimSpace(host) == "" {
		host = "127.0.0.1"
	}
	return &Engine{bin: bin, host: host, port: port, udcf: udcf, name: serverName}
}

func (e *Engine) SetLogHandler(fn func(string)) {
	e.mu.Lock()
	e.onLogLine = fn
	e.mu.Unlock()
}

func (e *Engine) Host() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if strings.TrimSpace(e.host) == "" {
		return "127.0.0.1"
	}
	return e.host
}

func (e *Engine) addrLocked() string {
	host := e.host
	if strings.TrimSpace(host) == "" {
		host = "127.0.0.1"
	}
	return net.JoinHostPort(host, strconv.Itoa(e.port))
}

func (e *Engine) Running() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cmd != nil && e.cmd.Process != nil {
		return true
	}
	return e.external
}

func (e *Engine) Start() error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cmd != nil && e.cmd.Process != nil {
		return nil
	}
	if err := EnsureUDCF(e.udcf); err != nil {
		slog.Warn("interactive udcf", "err", err)
	}
	for _, host := range e.candidatesLocked() {
		addr := net.JoinHostPort(host, strconv.Itoa(e.port))
		if err := waitTCP(addr, 200*time.Millisecond); err == nil {
			e.host = host
			e.external = true
			slog.Info("intiface using existing websocket", "addr", addr)
			return nil
		}
	}
	if !e.canSpawnLocked() {
		return fmt.Errorf("intiface websocket %s not listening (start host intiface-engine, or mount D-Bus so the api container can spawn one)", e.addrLocked())
	}
	if dbusSystemSocket() == "" {
		return fmt.Errorf("intiface websocket not listening on %s (start host intiface-engine on :%d, or mount /run/dbus/system_bus_socket)", e.addrLocked(), e.port)
	}

	// info: needed so "Device Added … object_path" lines reach us for BlueZ MAC persistence.
	args := []string{
		"--websocket-port", strconv.Itoa(e.port),
		"--server-name", e.name,
		"--max-ping-time", "1500",
		"--use-bluetooth-le",
		"--log", "info",
	}
	if e.udcf != "" {
		_ = os.MkdirAll(filepath.Dir(e.udcf), 0o755)
		args = append(args, "--user-device-config-file", e.udcf)
	}
	cmd := exec.Command(e.bin, args...)
	if sock := dbusSystemSocket(); sock == "" {
		slog.Warn("intiface: no system D-Bus socket; websocket will not bind. Mount /run/dbus/system_bus_socket into the api container.")
	} else {
		cmd.Env = append(os.Environ(), "DBUS_SYSTEM_BUS_ADDRESS=unix:path="+sock)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	e.cmd = cmd
	e.external = false
	e.host = "127.0.0.1"
	go e.pumpLogs(stdout)
	go e.pumpLogs(stderr)
	go func() {
		waitErr := cmd.Wait()
		e.mu.Lock()
		if e.cmd == cmd {
			e.cmd = nil
		}
		e.mu.Unlock()
		if waitErr != nil {
			slog.Warn("intiface-engine exited", "err", waitErr)
		} else {
			slog.Info("intiface-engine exited")
		}
	}()
	slog.Info("intiface-engine started", "port", e.port, "bin", e.bin)
	if err := waitTCP("127.0.0.1:"+strconv.Itoa(e.port), 8*time.Second); err != nil {
		_ = cmd.Process.Kill()
		e.cmd = nil
		return fmt.Errorf("intiface websocket :%d not listening (need host D-Bus at /run/dbus/system_bus_socket): %w", e.port, err)
	}
	return nil
}

func (e *Engine) candidatesLocked() []string {
	seen := map[string]struct{}{}
	var out []string
	add := func(host string) {
		host = strings.TrimSpace(host)
		if host == "" {
			return
		}
		if _, ok := seen[host]; ok {
			return
		}
		seen[host] = struct{}{}
		out = append(out, host)
	}
	configured := strings.TrimSpace(e.host)
	loopback := configured == "" || configured == "127.0.0.1" || configured == "localhost" || configured == "::1"
	if !loopback {
		add(configured)
	}
	add("127.0.0.1")
	for _, ip := range hostDockerInternalIPs() {
		add(ip)
	}
	add(defaultIPv4Gateway())
	add("172.17.0.1")
	return out
}

func (e *Engine) canSpawnLocked() bool {
	h := strings.ToLower(strings.TrimSpace(e.host))
	return h == "" || h == "127.0.0.1" || h == "localhost" || h == "::1"
}

func hostDockerInternalIPs() []string {
	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip4", "host.docker.internal")
	if err != nil {
		return nil
	}
	out := make([]string, 0, len(ips))
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil {
			out = append(out, v4.String())
		}
	}
	return out
}

func defaultIPv4Gateway() string {
	b, err := os.ReadFile("/proc/net/route")
	if err != nil {
		return ""
	}
	for i, line := range strings.Split(string(b), "\n") {
		if i == 0 {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 3 || fields[1] != "00000000" {
			continue
		}
		if ip := parseRouteGateway(fields[2]); ip != "" && ip != "0.0.0.0" {
			return ip
		}
	}
	return ""
}

func parseRouteGateway(hexLE string) string {
	if len(hexLE) != 8 {
		return ""
	}
	n, err := strconv.ParseUint(hexLE, 16, 32)
	if err != nil {
		return ""
	}
	ip := make(net.IP, 4)
	binary.LittleEndian.PutUint32(ip, uint32(n))
	return ip.String()
}

func dbusSystemSocket() string {
	for _, p := range []string{"/run/dbus/system_bus_socket", "/var/run/dbus/system_bus_socket"} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func waitTCP(addr string, d time.Duration) error {
	deadline := time.Now().Add(d)
	var last error
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", addr, 250*time.Millisecond)
		if err == nil {
			_ = c.Close()
			return nil
		}
		last = err
		time.Sleep(150 * time.Millisecond)
	}
	if last == nil {
		last = fmt.Errorf("timeout")
	}
	return last
}

func (e *Engine) pumpLogs(r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Text()
		_, _ = io.WriteString(os.Stdout, line+"\n")
		e.mu.Lock()
		fn := e.onLogLine
		e.mu.Unlock()
		if fn != nil {
			fn(line)
		}
	}
}

func (e *Engine) Stop() {
	e.mu.Lock()
	cmd := e.cmd
	e.cmd = nil
	e.external = false
	e.mu.Unlock()
	if cmd == nil || cmd.Process == nil {
		return
	}
	_ = cmd.Process.Signal(os.Interrupt)
	done := make(chan struct{})
	go func() {
		_, _ = cmd.Process.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		_ = cmd.Process.Kill()
	}
}

func (e *Engine) Restart() error {
	e.Stop()
	time.Sleep(400 * time.Millisecond)
	return e.Start()
}
