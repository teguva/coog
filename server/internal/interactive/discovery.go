package interactive

import (
	"context"
	"log/slog"
	"sync"
	"time"
)

const (
	discoveryBudget        = 75 * time.Second
	absentRescanEvery      = 45 * time.Second
	discoveryWatchdogEvery = 6 * time.Second
	rescanDebounce         = 3 * time.Second
	maintDeviceListEvery   = 10 * time.Second
)

// DiscoveryCoordinator debounces rescans, runs a discovery budget watchdog,
// and periodically retries absent wanted toys (Funplay-style soft recovery).
type DiscoveryCoordinator struct {
	svc *Service

	mu              sync.Mutex
	rescanAfter     time.Time
	lastMaint       time.Time
	lastWatchdog    time.Time
	lastAbsent      time.Time
	pairingUntil    time.Time
	discoveryUntil  time.Time
	discoveryReason string
}

func newDiscovery(svc *Service) *DiscoveryCoordinator {
	return &DiscoveryCoordinator{
		svc:        svc,
		lastAbsent: time.Now(),
	}
}

func (d *DiscoveryCoordinator) RequestRescan(reason string) {
	d.mu.Lock()
	d.rescanAfter = time.Now().Add(rescanDebounce)
	d.mu.Unlock()
	slog.Debug("interactive rescan scheduled", "reason", reason)
}

// BeginDiscovery opens or extends a discovery budget window and schedules an immediate scan.
func (d *DiscoveryCoordinator) BeginDiscovery(budget time.Duration, reason string) {
	if budget <= 0 {
		budget = discoveryBudget
	}
	now := time.Now()
	d.mu.Lock()
	until := now.Add(budget)
	if until.After(d.discoveryUntil) {
		d.discoveryUntil = until
	}
	d.discoveryReason = reason
	d.rescanAfter = now // scan ASAP on next loop tick
	d.lastAbsent = now
	d.mu.Unlock()
	slog.Info("interactive discovery started", "reason", reason, "budget_s", int(budget.Seconds()))
}

func (d *DiscoveryCoordinator) EndDiscovery(reason string) {
	d.mu.Lock()
	was := !d.discoveryUntil.IsZero() && time.Now().Before(d.discoveryUntil)
	d.discoveryUntil = time.Time{}
	d.discoveryReason = ""
	d.mu.Unlock()
	if was {
		slog.Info("interactive discovery ended", "reason", reason)
	}
}

func (d *DiscoveryCoordinator) DiscoveryActive() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return !d.discoveryUntil.IsZero() && time.Now().Before(d.discoveryUntil)
}

func (d *DiscoveryCoordinator) DiscoveryReason() string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.discoveryReason
}

func (d *DiscoveryCoordinator) StartPairing(window time.Duration) {
	d.mu.Lock()
	d.pairingUntil = time.Now().Add(window)
	d.mu.Unlock()
}

func (d *DiscoveryCoordinator) PairingActive() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return time.Now().Before(d.pairingUntil)
}

func (d *DiscoveryCoordinator) PairingSecondsLeft() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	sec := int(time.Until(d.pairingUntil).Seconds())
	if sec < 0 {
		return 0
	}
	return sec
}

func (d *DiscoveryCoordinator) Loop(ctx context.Context) {
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			d.tick()
		}
	}
}

func (d *DiscoveryCoordinator) tick() {
	now := time.Now()
	d.mu.Lock()
	doScan := !d.rescanAfter.IsZero() && now.After(d.rescanAfter)
	if doScan {
		d.rescanAfter = time.Time{}
	}
	doMaint := time.Since(d.lastMaint) > maintDeviceListEvery
	if doMaint {
		d.lastMaint = now
	}
	pairingEnded := !d.pairingUntil.IsZero() && now.After(d.pairingUntil)
	if pairingEnded {
		d.pairingUntil = time.Time{}
	}
	discoveryActive := !d.discoveryUntil.IsZero() && now.Before(d.discoveryUntil)
	discoveryEnded := !d.discoveryUntil.IsZero() && !now.Before(d.discoveryUntil)
	if discoveryEnded {
		d.discoveryUntil = time.Time{}
		d.discoveryReason = ""
	}
	doWatchdog := discoveryActive && time.Since(d.lastWatchdog) >= discoveryWatchdogEvery
	if doWatchdog {
		d.lastWatchdog = now
	}
	doAbsent := !discoveryActive && time.Since(d.lastAbsent) >= absentRescanEvery
	if doAbsent {
		d.lastAbsent = now
	}
	d.mu.Unlock()

	bp := d.svc.bp.Connected()
	if !bp {
		return
	}

	if discoveryEnded {
		d.svc.onDiscoveryBudgetEnded()
	}

	if doScan {
		if !d.svc.scanPaused() {
			_ = d.svc.bp.StartScanning(context.Background())
			d.svc.setScanning(true)
		}
	}
	if doMaint {
		_ = d.svc.bp.Command(context.Background(), "RequestDeviceList", map[string]any{})
	}
	if pairingEnded {
		_ = d.svc.bp.StopScanning(context.Background())
		d.svc.setScanning(false)
		d.svc.setPhaseIfNot("pairing", "idle")
	}
	if doWatchdog {
		// Funplay-style: keep discovery alive, but never undo an intentional
		// Lovense connect pause (parallel LVS connects fail on Linux).
		if d.svc.missingWantedCount() == 0 {
			d.EndDiscovery("all_linked")
			d.svc.onDiscoveryBudgetEnded()
		} else if !d.svc.scanPaused() {
			d.svc.mu.Lock()
			scanning := d.svc.scanning
			d.svc.mu.Unlock()
			if !scanning {
				_ = d.svc.bp.StartScanning(context.Background())
				d.svc.setScanning(true)
			}
		}
	}
	if doAbsent && d.svc.missingWantedCount() > 0 {
		d.svc.onAbsentRescan()
	}
}
