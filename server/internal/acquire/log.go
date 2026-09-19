package acquire

import (
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"coog/internal/events"
)

const logTailMax = 8 * 1024

type logSink struct {
	mu  sync.Mutex
	buf []byte
}

func newLogSink() *logSink {
	return &logSink{}
}

func (s *logSink) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.buf = append(s.buf, p...)
	if len(s.buf) > logTailMax {
		s.buf = s.buf[len(s.buf)-logTailMax:]
		if i := indexByte(s.buf, '\n'); i >= 0 {
			s.buf = s.buf[i+1:]
		}
	}
	return len(p), nil
}

func (s *logSink) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	text := strings.TrimSpace(string(s.buf))
	if !utf8.ValidString(text) {
		text = strings.ToValidUTF8(text, "")
	}
	return events.Redact(text)
}

func lastLogLine(tail string) string {
	tail = strings.TrimSpace(tail)
	if tail == "" {
		return ""
	}
	if i := strings.LastIndex(tail, "\n"); i >= 0 {
		return strings.TrimSpace(tail[i+1:])
	}
	return tail
}

func waitClosed(ch <-chan struct{}, timeout time.Duration) {
	select {
	case <-ch:
	case <-time.After(timeout):
	}
}

func waitWG(wg *sync.WaitGroup, timeout time.Duration) {
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	waitClosed(done, timeout)
}
