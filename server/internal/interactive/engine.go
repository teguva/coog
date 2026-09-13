package interactive

import (
	"bufio"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

// Engine hosts the intiface-engine child process.
type Engine struct {
	bin  string
	port int
	udcf string
	name string

	mu        sync.Mutex
	cmd       *exec.Cmd
	onLogLine func(string)
}

func NewEngine(bin string, port int, udcf, serverName string) *Engine {
	if serverName == "" {
		serverName = "coog-engine"
	}
	return &Engine{bin: bin, port: port, udcf: udcf, name: serverName}
}

func (e *Engine) SetLogHandler(fn func(string)) {
	e.mu.Lock()
	e.onLogLine = fn
	e.mu.Unlock()
}

func (e *Engine) Running() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.cmd != nil && e.cmd.Process != nil
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
	slog.Info("intiface-engine started", "port", e.port, "bin", e.bin)
	time.Sleep(800 * time.Millisecond)
	return nil
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
