package interactive

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"sort"
	"strings"
	"sync"
	"time"

	"coog/internal/config"
	"coog/internal/maize"
	"coog/internal/store"

	"github.com/coder/websocket"
)

type DevicePref struct {
	Index      int
	DeviceID   string
	Name       string
	Kind       string
	OffsetMs   int
	Intensity  int
	Connected  bool
	Paired     bool
	Profile    string
	Favorite   bool
	BatteryPct *int
}

type Service struct {
	cfg config.Config
	st  *store.Store

	engine *Engine
	bp     *ButtplugClient
	sync   *SyncRuntime
	disc   *DiscoveryCoordinator

	mu                sync.Mutex
	enabled           bool
	desiredRunning    bool // true only while a Maize adult session is active
	scanning          bool
	reconnectPh       string
	reconnectAttempts int
	reconnectReason   string
	lastError         string
	wanted            map[string]struct{}
	everConnected     map[string]struct{}
	powercycleTries   map[string]int
	bleAds            map[string]string // ble display name -> bluez object path / MAC
	deferredReconnect map[string]struct{}
	fleetSequential   bool
	fleetNextCancel   context.CancelFunc
	scanPauseUntil    time.Time // hold off watchdog StartScanning during Lovense connect
	prevLiveCount     int
	wedgedRecoverAt   map[string]time.Time // mac -> last wedged clear
	reconnectBusy     bool
	clearedStaleOnce  bool
	engineClients     map[*websocket.Conn]struct{}
	cancel            context.CancelFunc
}

func NewService(cfg config.Config, st *store.Store) *Service {
	s := &Service{
		cfg:               cfg,
		st:                st,
		enabled:           cfg.IntifaceEnabled,
		wanted:            map[string]struct{}{},
		everConnected:     map[string]struct{}{},
		powercycleTries:   map[string]int{},
		bleAds:            map[string]string{},
		deferredReconnect: map[string]struct{}{},
		wedgedRecoverAt:   map[string]time.Time{},
		engineClients:     map[*websocket.Conn]struct{}{},
		reconnectPh:       "idle",
	}
	s.engine = NewEngine(cfg.IntifaceBin, cfg.IntifacePort, cfg.IntifaceUDCF, "coog-engine")
	s.bp = NewButtplugClient(cfg.IntifacePort)
	s.sync = newSyncRuntime(s)
	s.disc = newDiscovery(s)
	s.engine.SetLogHandler(s.handleEngineLogLine)
	s.bp.SetOnChange(func() {
		s.onDevicesChanged()
		s.maybeFinishDiscovery()
		s.broadcastEngine()
	})
	s.bp.SetOnDeviceRemoved(func(deviceID string) {
		s.onDeviceRemoved(deviceID)
	})
	return s
}

func (s *Service) Enabled() bool { return s.enabled }

// SetDesired starts or stops intiface based on Maize adult-session presence.
func (s *Service) SetDesired(on bool) {
	if !s.enabled {
		return
	}
	s.mu.Lock()
	was := s.desiredRunning
	s.desiredRunning = on
	s.mu.Unlock()
	if on == was {
		return
	}
	if on {
		slog.Info("interactive armed (maize session active)")
		s.mu.Lock()
		doClear := !s.clearedStaleOnce
		if doClear {
			s.clearedStaleOnce = true
		}
		s.mu.Unlock()
		if doClear {
			go clearStaleToyLinks()
		}
		s.broadcastEngine()
		return
	}
	slog.Info("interactive disarmed (no maize session); shutting down intiface")
	s.shutdownHardware()
	s.setPhase("idle")
	s.setScanning(false)
	s.mu.Lock()
	s.reconnectReason = ""
	s.mu.Unlock()
	s.broadcastEngine()
}

func (s *Service) Desired() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.desiredRunning
}

func (s *Service) shutdownHardware() {
	s.HaltDevices()
	s.bp.Close()
	s.engine.Stop()
}

func (s *Service) Start(ctx context.Context) {
	if !s.enabled {
		slog.Info("interactive disabled (set COOG_INTIFACE_ENABLED=true and install intiface-engine)")
		return
	}
	if _, err := MigrateFunplayState(s.st, s.cfg.FunplayState); err != nil {
		slog.Warn("funplay migrate", "err", err)
	}
	s.seedWantedFromPaired()
	// Engine stays off until SetDesired(true) — Maize unlock on a client.
	slog.Info("interactive ready (intiface starts when maize is unlocked)")
	runCtx, cancel := context.WithCancel(ctx)
	s.cancel = cancel
	go s.sync.DriveLoop(runCtx)
	go s.disc.Loop(runCtx)
	go s.maintainLoop(runCtx)
	go s.batteryLoop(runCtx)
}

func (s *Service) Stop() {
	if s.cancel != nil {
		s.cancel()
	}
	s.mu.Lock()
	s.desiredRunning = false
	s.mu.Unlock()
	s.shutdownHardware()
}

func (s *Service) seedWantedFromPaired() {
	devices, err := s.st.ListInteractiveDevices()
	if err != nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range devices {
		if d.Paired {
			s.wanted[d.DeviceID] = struct{}{}
		}
	}
}

// onSessionReady mirrors Funplay auto-link: ensure wanted=paired and open a discovery budget.
func (s *Service) onSessionReady(reason string) {
	s.seedWantedFromPaired()
	s.mu.Lock()
	s.reconnectAttempts++
	s.reconnectReason = reason
	s.reconnectPh = "reconnect_scan"
	s.mu.Unlock()
	s.armFleetSequential()
	s.disc.BeginDiscovery(discoveryBudget, reason)
	s.setScanning(true)
	if err := s.bp.StartScanning(context.Background()); err != nil {
		s.setError(err.Error())
	}
	s.broadcastEngine()
	slog.Info("interactive session ready", "reason", reason, "missing", s.missingWantedCount())
}

func clearStaleToyLinks() {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "bluetoothctl", "devices", "Connected").Output()
	if err != nil {
		// Older bluetoothctl: fall back to all devices and disconnect likely toys.
		out, err = exec.CommandContext(ctx, "bluetoothctl", "devices").Output()
		if err != nil {
			return
		}
	}
	for _, line := range strings.Split(string(out), "\n") {
		m := reBluetoothctlDev.FindStringSubmatch(strings.TrimSpace(line))
		if m == nil {
			continue
		}
		mac, name := m[1], strings.ToLower(m[2])
		if strings.HasPrefix(strings.ToUpper(name), "LVS-") ||
			strings.Contains(name, "lovense") ||
			strings.Contains(name, "vortex") ||
			strings.Contains(name, "sexverse") ||
			strings.Contains(name, "vacuum") {
			bluezDisconnect(mac)
		}
	}
}

func (s *Service) onDeviceRemoved(deviceID string) {
	s.mu.Lock()
	s.prevLiveCount = len(s.bp.Devices())
	_, want := s.wanted[deviceID]
	s.mu.Unlock()
	if !want || deviceID == "" {
		s.broadcastEngine()
		return
	}
	s.mu.Lock()
	s.reconnectAttempts++
	s.reconnectReason = "device_removed"
	s.reconnectPh = "reconnect_scan"
	busy := s.reconnectBusy
	s.mu.Unlock()
	s.broadcastEngine()
	if busy {
		s.deferReconnect(deviceID, "recovery already running")
		return
	}
	go s.powerCycleReconnect(deviceID)
}

func (s *Service) deferReconnect(deviceID, reason string) {
	s.mu.Lock()
	s.deferredReconnect[deviceID] = struct{}{}
	s.mu.Unlock()
	slog.Info("interactive drop queued", "device_id", deviceID, "reason", reason)
	go s.flushDeferredReconnects()
}

func (s *Service) flushDeferredReconnects() {
	for {
		s.mu.Lock()
		busy := s.reconnectBusy
		s.mu.Unlock()
		if busy {
			time.Sleep(500 * time.Millisecond)
			continue
		}
		missingList := s.missingWantedIDs()
		missing := map[string]struct{}{}
		for _, id := range missingList {
			missing[id] = struct{}{}
		}
		s.mu.Lock()
		var pick string
		for id := range s.deferredReconnect {
			if _, ok := missing[id]; ok {
				pick = id
				break
			}
		}
		if pick == "" {
			if len(missingList) == 0 {
				s.deferredReconnect = map[string]struct{}{}
				s.mu.Unlock()
				return
			}
			pick = missingList[0]
		}
		delete(s.deferredReconnect, pick)
		s.mu.Unlock()
		if s.deviceLive(pick) {
			continue
		}
		s.powerCycleReconnect(pick)
		return
	}
}

const (
	dropSettleS         = 800 * time.Millisecond
	sessionLinkPollS    = 350 * time.Millisecond
	powercycleClearMax  = 1
	fleetConnectSettleS = 600 * time.Millisecond
	// Keep the discovery watchdog from undoing a Lovense pause mid-connect.
	lovenseConnectHoldS = 4 * time.Second
)

// powerCycleReconnect mirrors Funplay: after a trusted drop, clear the stale BlueZ
// object for Lovense-class toys (disconnect+remove) then rescan. Vortex is disconnect-only.
func (s *Service) powerCycleReconnect(deviceID string) {
	s.mu.Lock()
	if s.reconnectBusy {
		s.mu.Unlock()
		s.deferReconnect(deviceID, "recovery already running")
		return
	}
	s.reconnectBusy = true
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.reconnectBusy = false
		pending := len(s.deferredReconnect) > 0
		s.mu.Unlock()
		if pending {
			go s.flushDeferredReconnects()
		}
	}()

	label := deviceID
	if row, err := s.st.GetInteractiveDevice(deviceID); err == nil && row.Name != "" {
		label = row.Name
	}
	slog.Info("interactive power-cycle reconnect", "device_id", deviceID, "label", label)

	// Stop scan while clearing so we don't race parallel Lovense connects.
	if s.bp.Connected() {
		_ = s.bp.StopScanning(context.Background())
		s.setScanning(false)
		time.Sleep(250 * time.Millisecond)
	}

	time.Sleep(dropSettleS)
	if s.bp.Connected() {
		_ = s.bp.Command(context.Background(), "RequestDeviceList", map[string]any{})
	}
	time.Sleep(sessionLinkPollS)
	if s.deviceLive(deviceID) {
		s.clearPowercycleTries(deviceID)
		slog.Info("interactive drop was transient; device back", "device_id", deviceID)
		s.maybeFinishDiscovery()
		s.broadcastEngine()
		return
	}

	s.seedWantedFromPaired()
	s.mu.Lock()
	_, ever := s.everConnected[deviceID]
	tries := s.powercycleTries[deviceID]
	s.mu.Unlock()

	if !ever {
		slog.Info("interactive drop before first link this session; scan only", "device_id", deviceID)
		s.beginReconnectScan("never_linked")
		return
	}

	mac := s.macForDevice(deviceID, label)

	if IsVortexStableID(deviceID) {
		s.bumpPowercycleTries(deviceID)
		slog.Info("interactive vortex reconnect: disconnect+scan (no bluez remove)", "mac", mac)
		if mac != "" {
			bluezDisconnect(mac)
		}
		time.Sleep(300 * time.Millisecond)
		s.beginReconnectScan("vortex_rescan")
		return
	}

	if mac != "" && tries < powercycleClearMax {
		s.bumpPowercycleTries(deviceID)
		slog.Info("interactive lovense-style reconnect: clear stale bluez object", "mac", mac, "try", tries+1)
		bluezDisconnect(mac)
		bluezRemove(mac)
		time.Sleep(400 * time.Millisecond)
		s.beginReconnectScan("powercycle_clear")
		return
	}

	// MAC unknown or clear already used — soft scan; absent loop continues later.
	slog.Info("interactive reconnect fallback: scan only", "device_id", deviceID, "mac", mac, "tries", tries)
	s.beginReconnectScan("powercycle_scan")
}

func (s *Service) beginReconnectScan(reason string) {
	s.mu.Lock()
	s.reconnectAttempts++
	s.reconnectReason = reason
	s.reconnectPh = "reconnect_scan"
	s.mu.Unlock()
	s.armFleetSequential()
	s.disc.BeginDiscovery(discoveryBudget, reason)
	s.setScanning(true)
	if s.bp.Connected() {
		_ = s.bp.StartScanning(context.Background())
	}
	s.broadcastEngine()
}

func (s *Service) armFleetSequential() {
	missing := s.missingWantedCount()
	paired := 0
	if rows, err := s.st.ListInteractiveDevices(); err == nil {
		for _, d := range rows {
			if d.Paired {
				paired++
			}
		}
	}
	s.mu.Lock()
	// Funplay only arms when missing≥2; never disarm here — budget end / full link clears it.
	if missing >= 2 && paired >= 2 {
		s.fleetSequential = true
	}
	fleet := s.fleetSequential
	s.mu.Unlock()
	if fleet && missing >= 2 {
		slog.Info("interactive fleet sequential scan", "missing", missing)
	}
}

func (s *Service) deviceLive(deviceID string) bool {
	for _, d := range s.bp.Devices() {
		if d.DeviceID == deviceID {
			return true
		}
	}
	return false
}

func (s *Service) bumpPowercycleTries(deviceID string) {
	s.mu.Lock()
	s.powercycleTries[deviceID]++
	s.mu.Unlock()
}

func (s *Service) clearPowercycleTries(deviceID string) {
	s.mu.Lock()
	delete(s.powercycleTries, deviceID)
	s.mu.Unlock()
}

func (s *Service) macForDevice(deviceID, label string) string {
	if row, err := s.st.GetInteractiveDevice(deviceID); err == nil {
		if mac := NormalizeMAC(row.BleAddress); mac != "" {
			// Guard against previously mis-attributed MACs (any-LVS↔lovense bug).
			if row.BleName == "" || bleNameMatchesDevice(row.BleName, deviceID, row.Name, "") {
				return mac
			}
			slog.Warn("interactive ignoring mismatched stored ble address",
				"device_id", deviceID, "ble_name", row.BleName, "mac", mac)
		}
		if row.BleName != "" {
			s.mu.Lock()
			path := s.bleAds[row.BleName]
			s.mu.Unlock()
			if mac := NormalizeMAC(path); mac != "" {
				return mac
			}
		}
	}
	s.mu.Lock()
	for name, path := range s.bleAds {
		if bleNameMatchesDevice(name, deviceID, label, "") {
			s.mu.Unlock()
			return NormalizeMAC(path)
		}
	}
	s.mu.Unlock()
	hints := []string{label, strings.TrimPrefix(deviceID, "name:")}
	if row, err := s.st.GetInteractiveDevice(deviceID); err == nil {
		hints = append(hints, row.Name, row.BleName)
	}
	return bluezFindMAC(hints...)
}

func (s *Service) handleEngineLogLine(line string) {
	// Advertisements / Device Added — learn MACs and pause for Lovense connect.
	if bleName, objectPath, ok := parseEngineLogLine(line); ok {
		s.noteBleAdvertisement(bleName, objectPath)
		if isLVSAdvertisement(bleName) {
			s.pauseScanForLovenseConnect(bleName)
		}
	}

	// Funplay: InvalidEndpoint("rx") on a live link → disconnect.
	// "Device errored while trying to connect: InvalidEndpoint" is a failed
	// connect against a stale object — clear with remove so the next scan
	// re-enumerates (disconnect alone left Gush invisible in practice).
	if strings.Contains(line, "InvalidEndpoint") && strings.Contains(line, "rx") {
		mac := MacFromObjectPath(line)
		name := engineDeviceNameFromLog(line)
		if name == "" {
			name = "toy"
		}
		if mac != "" && strings.Contains(line, "Device errored while trying to connect") {
			go s.recoverWedgedConnect(mac, name)
		} else if mac != "" {
			go s.recoverRxWedged(mac)
		} else if s.missingWantedCount() > 0 {
			s.beginReconnectScan("lovense_rx_collision")
		}
		return
	}

	// Funplay: connect failure against stale bluez object → disconnect+remove.
	if reDeviceConnectFail.MatchString(line) {
		name := engineDeviceNameFromLog(line)
		if name == "" {
			name = "toy"
		}
		mac := MacFromObjectPath(line)
		if mac != "" {
			go s.recoverWedgedConnect(mac, name)
		} else if s.missingWantedCount() > 0 {
			s.beginReconnectScan("connect_failed")
		}
	}
}

func (s *Service) recoverRxWedged(mac string) {
	mac = NormalizeMAC(mac)
	if mac == "" {
		return
	}
	slog.Info("interactive recovery: freeing wedged BLE endpoint", "mac", mac)
	if s.bp.Connected() {
		_ = s.bp.StopScanning(context.Background())
		s.setScanning(false)
	}
	bluezDisconnect(mac)
	time.Sleep(400 * time.Millisecond)
	s.mu.Lock()
	fleet := s.fleetSequential
	s.mu.Unlock()
	if fleet {
		s.scheduleFleetNext("after rx bluez-disconnect")
		return
	}
	if s.missingWantedCount() > 0 {
		s.beginReconnectScan("after_rx_bluez_disconnect")
	}
}

func (s *Service) recoverWedgedConnect(mac, name string) {
	mac = NormalizeMAC(mac)
	if mac == "" {
		return
	}
	s.mu.Lock()
	last := s.wedgedRecoverAt[mac]
	if !last.IsZero() && time.Since(last) < 8*time.Second {
		s.mu.Unlock()
		return
	}
	s.wedgedRecoverAt[mac] = time.Now()
	s.mu.Unlock()

	if s.bp.Connected() {
		_ = s.bp.StopScanning(context.Background())
		s.setScanning(false)
	}

	if IsVortexName(name) || IsVortexStableID(StableDeviceID(name)) {
		slog.Info("interactive recovery: vortex connect failed — disconnect only", "name", name, "mac", mac)
		bluezDisconnect(mac)
		time.Sleep(400 * time.Millisecond)
		if s.missingWantedCount() > 0 {
			s.beginReconnectScan("after_vortex_wedged_clear")
		}
		return
	}
	slog.Info("interactive recovery: connect failed — clearing stale bluez object", "name", name, "mac", mac)
	bluezDisconnect(mac)
	bluezRemove(mac)
	time.Sleep(400 * time.Millisecond)
	s.mu.Lock()
	fleet := s.fleetSequential
	s.mu.Unlock()
	if fleet {
		s.scheduleFleetNext("after wedged-connect clear")
		return
	}
	if s.missingWantedCount() > 0 {
		s.beginReconnectScan("after_wedged_connect_clear")
	}
}

func (s *Service) noteBleAdvertisement(bleName, objectPath string) {
	bleName = strings.TrimSpace(bleName)
	if bleName == "" || objectPath == "" {
		return
	}
	s.mu.Lock()
	s.bleAds[bleName] = objectPath
	s.mu.Unlock()

	devices, err := s.st.ListInteractiveDevices()
	if err != nil {
		return
	}
	mac := NormalizeMAC(objectPath)
	for _, d := range devices {
		if !d.Paired {
			continue
		}
		if !bleNameMatchesDevice(bleName, d.DeviceID, d.Name, d.BleName) {
			continue
		}
		d.BleName = bleName
		d.BleAddress = objectPath
		d.UpdatedAt = time.Now().Unix()
		_ = s.st.UpsertInteractiveDevice(d)
		slog.Info("interactive ble address saved", "device_id", d.DeviceID, "ble_name", bleName, "mac", mac)
		return
	}
	// Single missing wanted + LVS advertisement → attach MAC to that toy.
	missing := s.missingWantedIDs()
	if len(missing) == 1 && strings.HasPrefix(strings.ToUpper(bleName), "LVS-") {
		id := missing[0]
		row, err := s.st.GetInteractiveDevice(id)
		if err != nil {
			row = store.InteractiveDevice{DeviceID: id, Name: bleName, Kind: "scalar", Paired: true, Intensity: 100, OffsetMs: 350, OffsetLinearMs: 350, Profile: "other"}
		}
		row.BleName = bleName
		row.BleAddress = objectPath
		row.UpdatedAt = time.Now().Unix()
		_ = s.st.UpsertInteractiveDevice(row)
		slog.Info("interactive ble address attached to missing wanted", "device_id", id, "ble_name", bleName, "mac", mac)
	}
}

func (s *Service) pauseScanForLovenseConnect(bleName string) {
	// Funplay: pause for any missing wanted LVS when multiple toys are paired
	// (not only while fleetSequential is armed).
	s.mu.Lock()
	scanning := s.scanning
	s.mu.Unlock()
	if !scanning {
		return
	}
	if !s.bleNameIsMissingWanted(bleName) {
		return
	}
	paired := 0
	if rows, err := s.st.ListInteractiveDevices(); err == nil {
		for _, d := range rows {
			if d.Paired {
				paired++
			}
		}
	}
	if paired < 2 && s.missingWantedCount() == 0 && !s.disc.PairingActive() {
		return
	}
	slog.Info("interactive scan pause for lovense connect", "ble_name", bleName)
	s.mu.Lock()
	s.scanPauseUntil = time.Now().Add(lovenseConnectHoldS)
	s.scanning = false
	s.mu.Unlock()
	_ = s.bp.StopScanning(context.Background())
	s.broadcastEngine()
}

func (s *Service) scanPaused() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return !s.scanPauseUntil.IsZero() && time.Now().Before(s.scanPauseUntil)
}

func (s *Service) clearScanPause() {
	s.mu.Lock()
	s.scanPauseUntil = time.Time{}
	s.mu.Unlock()
}

func (s *Service) bleNameIsMissingWanted(bleName string) bool {
	if s.disc.PairingActive() {
		return isLVSAdvertisement(bleName)
	}
	for _, d := range s.bp.Devices() {
		row, _ := s.st.GetInteractiveDevice(d.DeviceID)
		if bleNameMatchesDevice(bleName, d.DeviceID, d.Name, row.BleName) {
			return false
		}
	}
	for _, id := range s.missingWantedIDs() {
		row, err := s.st.GetInteractiveDevice(id)
		name := id
		stored := ""
		if err == nil {
			name = row.Name
			stored = row.BleName
		}
		if bleNameMatchesDevice(bleName, id, name, stored) {
			return true
		}
	}
	missing := s.missingWantedIDs()
	return len(missing) == 1 && isLVSAdvertisement(bleName)
}

func (s *Service) scheduleFleetNext(reason string) {
	s.mu.Lock()
	if !s.fleetSequential {
		s.mu.Unlock()
		return
	}
	if s.fleetNextCancel != nil {
		s.fleetNextCancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	s.fleetNextCancel = cancel
	s.mu.Unlock()
	go func() {
		select {
		case <-ctx.Done():
			return
		case <-time.After(fleetConnectSettleS):
		}
		s.fleetNextAfterDelay(reason)
	}()
}

func (s *Service) fleetNextAfterDelay(reason string) {
	s.mu.Lock()
	fleet := s.fleetSequential
	s.mu.Unlock()
	if !fleet || s.disc.PairingActive() {
		return
	}
	missing := s.missingWantedCount()
	if missing == 0 {
		s.mu.Lock()
		s.fleetSequential = false
		s.scanPauseUntil = time.Time{}
		s.mu.Unlock()
		slog.Info("interactive fleet: all trusted toys connected")
		if s.bp.Connected() {
			_ = s.bp.StopScanning(context.Background())
		}
		s.setScanning(false)
		s.broadcastEngine()
		return
	}
	s.mu.Lock()
	scanning := s.scanning
	s.mu.Unlock()
	if scanning {
		return
	}
	if !s.disc.DiscoveryActive() {
		s.mu.Lock()
		s.fleetSequential = false
		s.scanPauseUntil = time.Time{}
		s.mu.Unlock()
		return
	}
	s.clearScanPause()
	slog.Info("interactive fleet: resume scan", "reason", reason, "missing", missing)
	s.setScanning(true)
	if s.bp.Connected() {
		_ = s.bp.StartScanning(context.Background())
	}
	s.broadcastEngine()
}

func (s *Service) missingWantedIDs() []string {
	live := map[string]struct{}{}
	for _, d := range s.bp.Devices() {
		live[d.DeviceID] = struct{}{}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, 0)
	for id := range s.wanted {
		if _, ok := live[id]; !ok {
			out = append(out, id)
		}
	}
	return out
}

func (s *Service) onAbsentRescan() {
	s.mu.Lock()
	s.reconnectAttempts++
	s.reconnectReason = "absent_rescan"
	s.reconnectPh = "reconnect_scan"
	s.mu.Unlock()
	s.armFleetSequential()
	s.disc.BeginDiscovery(discoveryBudget, "absent_rescan")
	s.setScanning(true)
	_ = s.bp.StartScanning(context.Background())
	s.broadcastEngine()
	slog.Info("interactive absent rescan", "missing", s.missingWantedCount())
}

func (s *Service) onDiscoveryBudgetEnded() {
	s.setScanning(false)
	if s.bp.Connected() {
		_ = s.bp.StopScanning(context.Background())
	}
	s.mu.Lock()
	s.reconnectPh = "idle"
	s.reconnectReason = ""
	s.fleetSequential = false
	if s.fleetNextCancel != nil {
		s.fleetNextCancel()
		s.fleetNextCancel = nil
	}
	s.mu.Unlock()
	s.broadcastEngine()
}

func (s *Service) maybeFinishDiscovery() {
	if !s.disc.DiscoveryActive() {
		return
	}
	if s.missingWantedCount() == 0 {
		s.mu.Lock()
		s.fleetSequential = false
		if s.fleetNextCancel != nil {
			s.fleetNextCancel()
			s.fleetNextCancel = nil
		}
		s.mu.Unlock()
		s.disc.EndDiscovery("all_linked")
		s.onDiscoveryBudgetEnded()
	}
}

func (s *Service) missingWantedCount() int {
	live := map[string]struct{}{}
	for _, d := range s.bp.Devices() {
		live[d.DeviceID] = struct{}{}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for id := range s.wanted {
		if _, ok := live[id]; !ok {
			n++
		}
	}
	return n
}

func (s *Service) maintainLoop(ctx context.Context) {
	backoff := time.Second
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		if !s.Desired() {
			if s.engine.Running() || s.bp.Connected() {
				s.shutdownHardware()
			}
			backoff = time.Second
			time.Sleep(2 * time.Second)
			continue
		}
		if !s.engine.Running() {
			s.setPhase("disconnected")
			if err := s.engine.Start(); err != nil {
				s.setError(err.Error())
				slog.Warn("intiface start failed", "err", err)
				time.Sleep(backoff)
				if backoff < 12*time.Second {
					backoff *= 2
				}
				continue
			}
			backoff = time.Second
			time.Sleep(time.Second)
		}
		if !s.bp.Connected() {
			s.setPhase("disconnected")
			s.broadcastEngine()
			dialCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
			err := s.bp.Dial(dialCtx)
			cancel()
			if err != nil {
				s.setError(err.Error())
				// Process can stay up while the websocket port is dead.
				if s.engine.Running() && strings.Contains(err.Error(), "connection refused") {
					slog.Warn("intiface websocket refused; restarting engine")
					s.bp.Close()
					_ = s.engine.Restart()
					time.Sleep(time.Second)
				}
				time.Sleep(backoff)
				if backoff < 12*time.Second {
					backoff *= 2
				}
				continue
			}
			s.setError("")
			backoff = time.Second
			s.onSessionReady("session_ready")
		}
		time.Sleep(2 * time.Second)
	}
}

func (s *Service) batteryLoop(ctx context.Context) {
	// First read soon after arm so chrome rings aren't empty for a minute.
	t := time.NewTimer(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.RefreshBattery()
			t.Reset(30 * time.Second)
		}
	}
}

func (s *Service) setError(msg string) {
	s.mu.Lock()
	s.lastError = msg
	s.mu.Unlock()
}

func (s *Service) setPhase(ph string) {
	s.mu.Lock()
	s.reconnectPh = ph
	s.mu.Unlock()
}

func (s *Service) setPhaseIfNot(current, next string) {
	s.mu.Lock()
	if s.reconnectPh == current {
		s.reconnectPh = next
	}
	s.mu.Unlock()
}

func (s *Service) setScanning(v bool) {
	s.mu.Lock()
	s.scanning = v
	s.mu.Unlock()
	s.broadcastEngine()
}

func (s *Service) onDevicesChanged() {
	now := time.Now().Unix()
	for _, live := range s.bp.Devices() {
		existing, err := s.st.GetInteractiveDevice(live.DeviceID)
		d := store.InteractiveDevice{
			DeviceID:        live.DeviceID,
			Name:            live.Name,
			Kind:            live.Kind,
			OffsetMs:        350,
			OffsetLinearMs:  350,
			Intensity:       100,
			Profile:         "other",
			LastConnectedAt: now,
			UpdatedAt:       now,
		}
		if err == nil {
			d = existing
			d.Name = live.Name
			d.Kind = live.Kind
			d.LastConnectedAt = now
			d.UpdatedAt = now
		}
		if live.BatterySensorIndex != nil {
			d.BatterySupported = true
		}
		if live.BatteryPercent != nil {
			d.BatteryPercent = *live.BatteryPercent
			d.BatteryUpdatedAt = now
		}
		// Attach pending BLE advertisement MAC if we have one.
		if d.BleAddress == "" {
			s.mu.Lock()
			for bleName, path := range s.bleAds {
				if bleNameMatchesDevice(bleName, live.DeviceID, live.Name, d.BleName) {
					d.BleName = bleName
					d.BleAddress = path
					break
				}
			}
			s.mu.Unlock()
		}
		// Auto-pair during pairing window.
		if s.disc.PairingActive() && !d.Paired {
			d.Paired = true
			s.mu.Lock()
			s.wanted[d.DeviceID] = struct{}{}
			s.mu.Unlock()
			_ = s.bp.StopScanning(context.Background())
			s.setScanning(false)
		}
		_ = s.st.UpsertInteractiveDevice(d)
		s.mu.Lock()
		_, wasLive := s.everConnected[live.DeviceID]
		s.everConnected[live.DeviceID] = struct{}{}
		delete(s.powercycleTries, live.DeviceID)
		delete(s.deferredReconnect, live.DeviceID)
		s.mu.Unlock()
		if !wasLive {
			// First link this session — pull battery right away for chrome rings.
			go s.RefreshBattery()
		}
	}
	s.mu.Lock()
	fleet := s.fleetSequential
	grew := len(s.bp.Devices()) > s.prevLiveCount
	s.prevLiveCount = len(s.bp.Devices())
	s.mu.Unlock()
	// Funplay: on DeviceAdded during fleet, stop scan then resume after settle.
	if fleet && grew {
		if s.missingWantedCount() == 0 {
			s.mu.Lock()
			s.fleetSequential = false
			s.scanPauseUntil = time.Time{}
			s.mu.Unlock()
			_ = s.bp.StopScanning(context.Background())
			s.setScanning(false)
			s.maybeFinishDiscovery()
			return
		}
		_ = s.bp.StopScanning(context.Background())
		s.setScanning(false)
		s.scheduleFleetNext("device connected")
	}
}

func (s *Service) LiveDevicesWithPrefs() []DevicePref {
	live := s.bp.Devices()
	out := make([]DevicePref, 0, len(live))
	for _, d := range live {
		pref := DevicePref{
			Index:     d.Index,
			DeviceID:  d.DeviceID,
			Name:      d.Name,
			Kind:      d.Kind,
			OffsetMs:  350,
			Intensity: 100,
			Connected: true,
		}
		if row, err := s.st.GetInteractiveDevice(d.DeviceID); err == nil {
			pref.OffsetMs = row.OffsetMs
			if row.Kind == "linear" || row.Kind == "constrict" {
				if row.OffsetLinearMs > 0 {
					pref.OffsetMs = row.OffsetLinearMs
				}
			}
			pref.Intensity = row.Intensity
			pref.Paired = row.Paired
			pref.Profile = row.Profile
			pref.Favorite = row.Favorite
			pref.Kind = row.Kind
			if row.Kind == "" {
				pref.Kind = d.Kind
			}
			// Prefer live reading; fall back to last stored so chrome shows battery immediately.
			if d.BatteryPercent != nil {
				pref.BatteryPct = d.BatteryPercent
			} else if row.BatterySupported && row.BatteryPercent >= 0 {
				pct := row.BatteryPercent
				pref.BatteryPct = &pct
			}
		} else if d.BatteryPercent != nil {
			pref.BatteryPct = d.BatteryPercent
		}
		out = append(out, pref)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].DeviceID != out[j].DeviceID {
			return out[i].DeviceID < out[j].DeviceID
		}
		return out[i].Index < out[j].Index
	})
	return out
}

func (s *Service) HaltDevices() {
	if !s.bp.Connected() {
		return
	}
	ctx := context.Background()
	for _, d := range s.bp.Devices() {
		switch d.Kind {
		case "linear":
			_ = s.bp.StopDevice(ctx, d.Index)
		default:
			act := "Vibrate"
			if d.Kind == "constrict" {
				act = "Constrict"
			}
			_ = s.bp.Scalar(ctx, d.Index, 0, act)
		}
	}
}

func (s *Service) Status() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	return map[string]any{
		"supported":      s.enabled,
		"enabled":        s.enabled,
		"armed":          s.desiredRunning,
		"engineRunning":  s.engine.Running(),
		"connected":      s.bp.Connected(),
		"scanning":       s.scanning,
		"pairing":        s.disc.PairingActive(),
		"deviceCount":    len(s.bp.Devices()),
		"lastError":      firstNonEmpty(s.lastError, s.bp.LastError()),
		"reconnectPhase": s.reconnectPh,
	}
}

func (s *Service) EngineState() map[string]any {
	live := s.LiveDevicesWithPrefs()
	devices := make([]map[string]any, 0, len(live))
	connectedIDs := map[string]struct{}{}
	for _, d := range live {
		connectedIDs[d.DeviceID] = struct{}{}
		row := map[string]any{
			"index":     d.Index,
			"deviceId":  d.DeviceID,
			"name":      d.Name,
			"kind":      d.Kind,
			"connected": true,
			"paired":    d.Paired,
			"profile":   d.Profile,
			"favorite":  d.Favorite,
			"offsetMs":  d.OffsetMs,
			"intensity": d.Intensity,
			"status":    "connected",
		}
		if d.BatteryPct != nil {
			row["batteryPercent"] = *d.BatteryPct
			row["batterySupported"] = true
		}
		devices = append(devices, row)
	}
	known, _ := s.st.ListInteractiveDevices()
	trusted := make([]map[string]any, 0)
	knownOut := make([]map[string]any, 0)
	s.mu.Lock()
	wanted := copySet(s.wanted)
	scanning := s.scanning
	phase := s.reconnectPh
	attempts := s.reconnectAttempts
	reason := s.reconnectReason
	lastErr := firstNonEmpty(s.lastError, s.bp.LastError())
	fleet := s.fleetSequential
	s.mu.Unlock()
	for _, d := range known {
		_, online := connectedIDs[d.DeviceID]
		_, want := wanted[d.DeviceID]
		status := "offline"
		if online {
			status = "connected"
		} else if want && s.disc.DiscoveryActive() {
			// Only "connecting" while a discovery budget is open — not for powered-off toys between retries.
			status = "connecting"
		}
		entry := map[string]any{
			"deviceId":         d.DeviceID,
			"name":             d.Name,
			"kind":             d.Kind,
			"connected":        online,
			"paired":           d.Paired,
			"profile":          d.Profile,
			"favorite":         d.Favorite,
			"offsetMs":         d.OffsetMs,
			"offsetLinearMs":   d.OffsetLinearMs,
			"intensity":        d.Intensity,
			"lastConnectedAt":  d.LastConnectedAt,
			"batterySupported": d.BatterySupported,
			"batteryPercent":   d.BatteryPercent,
			"status":           status,
			"wanted":           want,
		}
		if d.Paired {
			trusted = append(trusted, entry)
		}
		if !online {
			knownOut = append(knownOut, entry)
		}
	}
	sort.Slice(trusted, func(i, j int) bool {
		ai, _ := trusted[i]["deviceId"].(string)
		aj, _ := trusted[j]["deviceId"].(string)
		return ai < aj
	})
	sort.Slice(knownOut, func(i, j int) bool {
		ai, _ := knownOut[i]["deviceId"].(string)
		aj, _ := knownOut[j]["deviceId"].(string)
		return ai < aj
	})
	missing := 0
	for id := range wanted {
		if _, ok := connectedIDs[id]; !ok {
			missing++
		}
	}
	return map[string]any{
		"running":            s.engine.Running(),
		"connected":          s.bp.Connected(),
		"nativeEngine":       false,
		"scanning":           scanning,
		"reconnecting":       phase != "idle" && phase != "",
		"reconnectPhase":     phase,
		"pairing":            s.disc.PairingActive(),
		"pairingSecondsLeft": s.disc.PairingSecondsLeft(),
		"pairingHint":        "",
		"missingPaired":      missing,
		"fleetSequential":    fleet,
		"reconnectAttempts":  attempts,
		"reconnectReason":    reason,
		"lastError":          lastErr,
		"devices":            devices,
		"knownDevices":       knownOut,
		"trustedDevices":     trusted,
	}
}

func (s *Service) ScanStart() error {
	s.mu.Lock()
	if s.reconnectPh != "reconnect_scan" && s.reconnectPh != "pairing" {
		s.reconnectPh = "scanning"
	}
	s.scanning = true
	s.mu.Unlock()
	err := s.bp.StartScanning(context.Background())
	if err != nil {
		s.setError(err.Error())
	}
	s.broadcastEngine()
	return err
}

func (s *Service) ScanPair() error {
	s.disc.StartPairing(90 * time.Second)
	s.setPhase("pairing")
	return s.ScanStart()
}

func (s *Service) ScanStop() error {
	s.disc.EndDiscovery("user_stop")
	s.setScanning(false)
	s.setPhase("idle")
	s.mu.Lock()
	s.reconnectReason = ""
	s.mu.Unlock()
	err := s.bp.StopScanning(context.Background())
	s.broadcastEngine()
	return err
}

func (s *Service) Reconnect() error {
	if !s.Desired() {
		return fmt.Errorf("intiface runs only while maize is unlocked")
	}
	if !s.bp.Connected() {
		return fmt.Errorf("intiface not connected")
	}
	s.onSessionReady("manual")
	return nil
}

func (s *Service) RestartEngine() error {
	if !s.Desired() {
		return fmt.Errorf("intiface runs only while maize is unlocked")
	}
	s.setPhase("engine_restart")
	s.mu.Lock()
	s.reconnectReason = "engine_restart"
	s.mu.Unlock()
	s.HaltDevices()
	s.bp.Close()
	s.broadcastEngine()
	if err := s.engine.Restart(); err != nil {
		s.setError(err.Error())
		s.setPhase("disconnected")
		s.broadcastEngine()
		return err
	}
	time.Sleep(time.Second)
	dialCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	err := s.bp.Dial(dialCtx)
	if err != nil {
		s.setError(err.Error())
		s.setPhase("disconnected")
		s.broadcastEngine()
		return err
	}
	s.setError("")
	s.onSessionReady("engine_restart")
	return nil
}

func (s *Service) StopAll() error {
	err := s.bp.StopAll(context.Background())
	s.broadcastEngine()
	return err
}

func (s *Service) RefreshBattery() {
	if !s.bp.Connected() {
		return
	}
	ctx := context.Background()
	for _, d := range s.bp.Devices() {
		if d.BatterySensorIndex != nil {
			_ = s.bp.SensorRead(ctx, d.Index, *d.BatterySensorIndex)
		}
	}
}

func (s *Service) TestDevice(idx int) error {
	d, ok := s.bp.DeviceByIndex(idx)
	if !ok {
		return errNotFound("device")
	}
	ctx := context.Background()
	switch d.Kind {
	case "linear":
		_ = s.bp.Linear(ctx, idx, 0.8, 800)
		time.Sleep(800 * time.Millisecond)
		_ = s.bp.Linear(ctx, idx, 0.0, 800)
	case "constrict":
		_ = s.bp.Scalar(ctx, idx, 0.6, "Constrict")
		time.Sleep(600 * time.Millisecond)
		_ = s.bp.Scalar(ctx, idx, 0.0, "Constrict")
	default:
		_ = s.bp.Scalar(ctx, idx, 0.6, "Vibrate")
		time.Sleep(600 * time.Millisecond)
		_ = s.bp.Scalar(ctx, idx, 0.0, "Vibrate")
	}
	return nil
}

func (s *Service) PatchDevice(idx int, patch map[string]any) (store.InteractiveDevice, error) {
	d, ok := s.bp.DeviceByIndex(idx)
	if !ok {
		// Allow patching trusted offline by device list index? Prefer live only.
		return store.InteractiveDevice{}, errNotFound("device")
	}
	row, err := s.st.GetInteractiveDevice(d.DeviceID)
	if err != nil {
		row = store.InteractiveDevice{
			DeviceID: d.DeviceID, Name: d.Name, Kind: d.Kind,
			OffsetMs: 350, OffsetLinearMs: 350, Intensity: 100, Profile: "other",
		}
	}
	if v, ok := patch["profile"].(string); ok {
		row.Profile = SanitizeProfile(v)
	}
	if v, ok := patch["favorite"].(bool); ok {
		row.Favorite = v
	}
	if v, ok := patch["paired"].(bool); ok {
		row.Paired = v
		s.mu.Lock()
		if v {
			s.wanted[row.DeviceID] = struct{}{}
		} else {
			delete(s.wanted, row.DeviceID)
		}
		s.mu.Unlock()
	}
	if v, ok := asInt(patch["offsetMs"]); ok {
		row.OffsetMs = v
	}
	if v, ok := asInt(patch["offset_ms"]); ok {
		row.OffsetMs = v
	}
	if v, ok := asInt(patch["offsetLinearMs"]); ok {
		row.OffsetLinearMs = v
	}
	if v, ok := asInt(patch["intensity"]); ok {
		row.Intensity = ClampIntensity(v)
	}
	row.UpdatedAt = time.Now().Unix()
	if err := s.st.UpsertInteractiveDevice(row); err != nil {
		return row, err
	}
	s.broadcastEngine()
	return row, nil
}

func (s *Service) ConnectDevice(id string) error {
	s.mu.Lock()
	s.wanted[id] = struct{}{}
	s.mu.Unlock()
	if row, err := s.st.GetInteractiveDevice(id); err == nil {
		row.Paired = true
		row.UpdatedAt = time.Now().Unix()
		_ = s.st.UpsertInteractiveDevice(row)
	}
	return s.ScanStart()
}

func (s *Service) DisconnectDevice(id string) error {
	s.mu.Lock()
	delete(s.wanted, id)
	s.mu.Unlock()
	for _, d := range s.bp.Devices() {
		if d.DeviceID == id {
			_ = s.bp.StopDevice(context.Background(), d.Index)
		}
	}
	s.broadcastEngine()
	return nil
}

func (s *Service) ForgetDevice(id string) error {
	s.mu.Lock()
	delete(s.wanted, id)
	s.mu.Unlock()
	_ = s.DisconnectDevice(id)
	err := s.st.DeleteInteractiveDevice(id)
	s.broadcastEngine()
	return err
}

func (s *Service) ForgetOffline() (int64, error) {
	ids := make([]string, 0)
	for _, d := range s.bp.Devices() {
		ids = append(ids, d.DeviceID)
	}
	n, err := s.st.DeleteOfflineInteractiveDevices(ids)
	s.broadcastEngine()
	return n, err
}

func (s *Service) LoadScript(mediaID, mediaPath, scriptName string, params SyncParams, resumeMs float64) error {
	path := maize.ResolveFunscriptPath(mediaPath, scriptName)
	if path == "" {
		return errNotFound("funscript")
	}
	actions, err := maize.LoadFunscriptActions(path)
	if err != nil {
		return err
	}
	mapped := make([]Action, 0, len(actions))
	for _, a := range actions {
		mapped = append(mapped, Action{At: a.At, Pos: a.Pos})
	}
	// Cache preview + actions optionally.
	if st, err := os.Stat(path); err == nil {
		preview, _ := maize.LoadFunscriptPreview(path, 240)
		prevJSON, _ := json.Marshal(preview)
		actJSON, _ := json.Marshal(mapped)
		_ = s.st.UpsertFunscriptCache(store.FunscriptCacheRow{
			MediaID: mediaID, SourcePath: path, MtimeUnix: st.ModTime().Unix(),
			DurationMs: preview.DurationMs, ActionCount: preview.ActionCount,
			Intensity: preview.Intensity, PreviewJSON: string(prevJSON), ActionsBlob: actJSON,
		})
	}
	s.sync.Load(mediaID, mapped, params, resumeMs)
	sessID := randomID()
	paramsJSON, _ := json.Marshal(params)
	_ = s.st.InsertSyncSession(store.InteractiveSyncSession{
		ID: sessID, MediaID: mediaID, StartedAt: time.Now().Unix(), ParamsJSON: string(paramsJSON),
	})
	s.sync.mu.Lock()
	s.sync.sessionID = sessID
	s.sync.mu.Unlock()
	return nil
}

func (s *Service) SetParams(p SyncParams) { s.sync.SetParams(p) }

func (s *Service) PlaybackStatus() map[string]any { return s.sync.Status() }

func (s *Service) ServeSyncWS(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: true,
		OriginPatterns:     []string{"*"},
	})
	if err != nil {
		return
	}
	s.sync.AddClient(conn)
	defer func() {
		s.sync.RemoveClient(conn)
		_ = conn.Close(websocket.StatusNormalClosure, "")
	}()
	ctx := r.Context()
	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		if reply := s.sync.HandleMessage(conn, data); reply != nil {
			_ = conn.Write(ctx, websocket.MessageText, reply)
		}
	}
}

func (s *Service) ServeEngineWS(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: true,
		OriginPatterns:     []string{"*"},
	})
	if err != nil {
		return
	}
	s.mu.Lock()
	s.engineClients[conn] = struct{}{}
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		delete(s.engineClients, conn)
		s.mu.Unlock()
		_ = conn.Close(websocket.StatusNormalClosure, "")
	}()
	init, _ := json.Marshal(map[string]any{"type": "state"})
	// merge state
	state := s.EngineState()
	state["type"] = "state"
	init, _ = json.Marshal(state)
	_ = conn.Write(r.Context(), websocket.MessageText, init)
	ctx := r.Context()
	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		var msg map[string]any
		if json.Unmarshal(data, &msg) != nil {
			continue
		}
		switch msg["type"] {
		case "scan_start":
			_ = s.ScanStart()
		case "scan_stop":
			_ = s.ScanStop()
		case "stop_all":
			_ = s.StopAll()
		}
	}
}

func (s *Service) broadcastEngine() {
	state := s.EngineState()
	state["type"] = "state"
	payload, err := json.Marshal(state)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	s.mu.Lock()
	defer s.mu.Unlock()
	for conn := range s.engineClients {
		if err := conn.Write(ctx, websocket.MessageText, payload); err != nil {
			delete(s.engineClients, conn)
			_ = conn.CloseNow()
		}
	}
}

type simpleError string

func (e simpleError) Error() string { return string(e) }

func errNotFound(what string) error { return simpleError(what + " not found") }

func asInt(v any) (int, bool) {
	switch n := v.(type) {
	case float64:
		return int(n), true
	case int:
		return n, true
	case int64:
		return int(n), true
	case json.Number:
		i, err := n.Int64()
		return int(i), err == nil
	default:
		return 0, false
	}
}

func copySet(in map[string]struct{}) map[string]struct{} {
	out := make(map[string]struct{}, len(in))
	for k := range in {
		out[k] = struct{}{}
	}
	return out
}

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

func randomID() string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}
