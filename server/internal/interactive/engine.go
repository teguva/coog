package interactive

import (
	"bufio"
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

// Engine hosts the intiface-engine child process, or waits on a host process.
type Engine struct {
	bin  string
	host string
	port int
	udcf string
	name string

	mu        sync.Mutex
	cmd       *exec.Cmd
	onLogLine func(string)
}

func NewEngine(bin, host string, port int, udcf, serverName string) *Engine {
	if serverName == "" {
		serverName = "coog-engine"
	}
	if host == "" {
		host = "127.0.0.1"
	}
	return &Engine{bin: bin, host: host, port: port, udcf: udcf, name: serverName}
}

func (e *Engine) addr() string {
	return net.JoinHostPort(e.host, strconv.Itoa(e.port))
}

// External reports whether intiface is expected on another host (not spawned here).
func (e *Engine) External() bool {
	h := strings.ToLower(strings.TrimSpace(e.host))
	return h != "" && h != "127.0.0.1" && h != "localhost" && h != "::1"
}

func (e *Engine) SetLogHandler(fn func(string)) {
	e.mu.Lock()
	e.onLogLine = fn
	e.mu.Unlock()
}

func (e *Engine) Running() bool {
	if e.External() {
		c, err := net.DialTimeout("tcp", e.addr(), 250*time.Millisecond)
		if err == nil {
			_ = c.Close()
			return true
		}
		return false
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.cmd != nil && e.cmd.Process != nil
}

func (e *Engine) Start() error {
	if e.External() {
		if err := waitTCP(e.addr(), 3*time.Second); err != nil {
			return fmt.Errorf("intiface websocket %s not listening (start intiface-engine on the host): %w", e.addr(), err)
		}
		return nil
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cmd != nil && e.cmd.Process != nil {
		return nil
	}
	if err := EnsureUDCF(e.udcf); err != nil {
		slog.Warn("interactive udcf", "err", err)
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
	slog.Info("intiface-engine started", "host", e.host, "port", e.port, "bin", e.bin)
	if err := waitTCP(e.addr(), 8*time.Second); err != nil {
		_ = cmd.Process.Kill()
		e.cmd = nil
		return fmt.Errorf("intiface websocket %s not listening (need host D-Bus at /run/dbus/system_bus_socket): %w", e.addr(), err)
	}
	return nil
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
	if e.External() {
		return
	}
	e.mu.Lock()
	cmd := e.cmd
	e.cmd = nil
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
