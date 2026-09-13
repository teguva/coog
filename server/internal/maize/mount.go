package maize

import (
	"context"
	"fmt"
	"log/slog"
)

// MountBackend unlocks an at-rest encrypted Maize tree (phase 2).
// Phase 1 uses NoopMount — files live as a plain Maize/ folder under the library.
type MountBackend interface {
	// Unlock prepares the Maize content path for scan/stream. pin may wrap a separate secret later.
	Unlock(ctx context.Context, cfg Config, pin string) error
	// Lock tears down any mount. Safe to call when already locked.
	Lock(ctx context.Context, cfg Config) error
}

// NoopMount is the phase-1 backend (app lock only).
type NoopMount struct{}

func (NoopMount) Unlock(ctx context.Context, cfg Config, pin string) error {
	_ = ctx
	_ = pin
	if cfg.Encrypted {
		slog.Warn("maize encrypted flag set but no mount backend configured; serving plain path")
	}
	return nil
}

func (NoopMount) Lock(ctx context.Context, cfg Config) error {
	_ = ctx
	_ = cfg
	return nil
}

// ErrMountUnavailable is returned by future gocryptfs/LUKS backends when tools are missing.
var ErrMountUnavailable = fmt.Errorf("maize mount backend unavailable")
