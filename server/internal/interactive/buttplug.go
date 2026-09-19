package interactive

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

// LiveDevice is a currently connected Buttplug device.
type LiveDevice struct {
	Index              int
	DeviceID           string
	Name               string
	Kind               string // scalar|linear|constrict
	BatterySensorIndex *int
	BatteryPercent     *int
}

type ButtplugClient struct {
	host string
	port int

	mu        sync.Mutex
	conn      *websocket.Conn
	msgID     int64
	devices   map[int]*LiveDevice // index -> device
	onChange  func()
	onRemoved func(deviceID string)
	lastErr   string
	ready     atomic.Bool
}

func NewButtplugClient(host string, port int) *ButtplugClient {
	if host == "" {
		host = "127.0.0.1"
	}
	return &ButtplugClient{
		host:    host,
		port:    port,
		devices: map[int]*LiveDevice{},
		msgID:   1,
	}
}

func (c *ButtplugClient) SetOnChange(fn func()) {
	c.mu.Lock()
	c.onChange = fn
	c.mu.Unlock()
}

func (c *ButtplugClient) SetOnDeviceRemoved(fn func(deviceID string)) {
	c.mu.Lock()
	c.onRemoved = fn
	c.mu.Unlock()
}

func (c *ButtplugClient) Connected() bool { return c.ready.Load() }

func (c *ButtplugClient) LastError() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lastErr
}

func (c *ButtplugClient) Devices() []LiveDevice {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]LiveDevice, 0, len(c.devices))
	for _, d := range c.devices {
		out = append(out, *d)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].DeviceID != out[j].DeviceID {
			return out[i].DeviceID < out[j].DeviceID
		}
		return out[i].Index < out[j].Index
	})
	return out
}

func (c *ButtplugClient) DeviceByIndex(idx int) (*LiveDevice, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	d, ok := c.devices[idx]
	return d, ok
}

func (c *ButtplugClient) nextID() int {
	return int(atomic.AddInt64(&c.msgID, 1))
}

func (c *ButtplugClient) Dial(ctx context.Context) error {
	url := fmt.Sprintf("ws://%s", net.JoinHostPort(c.host, strconv.Itoa(c.port)))
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		HTTPClient: &http.Client{Timeout: 5 * time.Second},
	})
	if err != nil {
		c.mu.Lock()
		c.lastErr = err.Error()
		c.mu.Unlock()
		return err
	}
	c.mu.Lock()
	// Fresh session — never carry live devices across reconnects.
	c.devices = map[int]*LiveDevice{}
	c.conn = conn
	c.mu.Unlock()

	id := c.nextID()
	if err := c.sendRaw(ctx, []map[string]any{{
		"RequestServerInfo": map[string]any{
			"Id":             id,
			"ClientName":     "coog-api",
			"MessageVersion": 3,
		},
	}}); err != nil {
		_ = conn.Close(websocket.StatusNormalClosure, "")
		return err
	}
	_, data, err := conn.Read(ctx)
	if err != nil {
		_ = conn.Close(websocket.StatusNormalClosure, "")
		return err
	}
	var arr []map[string]json.RawMessage
	if json.Unmarshal(data, &arr) != nil || len(arr) == 0 {
		_ = conn.Close(websocket.StatusNormalClosure, "")
		return fmt.Errorf("bad ServerInfo response")
	}
	c.ready.Store(true)
	c.mu.Lock()
	c.lastErr = ""
	c.mu.Unlock()
	slog.Info("buttplug connected", "host", c.host, "port", c.port)

	_ = c.Command(ctx, "RequestDeviceList", map[string]any{})
	go c.pingLoop(conn)
	go c.readLoop(conn)
	return nil
}

func (c *ButtplugClient) pingLoop(conn *websocket.Conn) {
	t := time.NewTicker(500 * time.Millisecond)
	defer t.Stop()
	for range t.C {
		c.mu.Lock()
		same := c.conn == conn
		c.mu.Unlock()
		if !same || !c.ready.Load() {
			return
		}
		if err := c.Command(context.Background(), "Ping", map[string]any{}); err != nil {
			slog.Warn("buttplug ping failed; closing", "err", err)
			c.Close()
			return
		}
	}
}

func (c *ButtplugClient) Close() {
	c.ready.Store(false)
	c.mu.Lock()
	conn := c.conn
	c.conn = nil
	c.devices = map[int]*LiveDevice{}
	c.mu.Unlock()
	if conn != nil {
		_ = conn.Close(websocket.StatusNormalClosure, "")
	}
}

func (c *ButtplugClient) sendRaw(ctx context.Context, payload any) error {
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("not connected")
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	writeCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	return conn.Write(writeCtx, websocket.MessageText, b)
}

func (c *ButtplugClient) Command(ctx context.Context, name string, body map[string]any) error {
	if body == nil {
		body = map[string]any{}
	}
	body["Id"] = c.nextID()
	return c.sendRaw(ctx, []map[string]any{{name: body}})
}

func (c *ButtplugClient) StartScanning(ctx context.Context) error {
	return c.Command(ctx, "StartScanning", map[string]any{})
}

func (c *ButtplugClient) StopScanning(ctx context.Context) error {
	return c.Command(ctx, "StopScanning", map[string]any{})
}

func (c *ButtplugClient) StopAll(ctx context.Context) error {
	return c.Command(ctx, "StopAllDevices", map[string]any{})
}

func (c *ButtplugClient) StopDevice(ctx context.Context, idx int) error {
	return c.Command(ctx, "StopDeviceCmd", map[string]any{"DeviceIndex": idx})
}

func (c *ButtplugClient) Scalar(ctx context.Context, idx int, scalar float64, actuator string) error {
	if actuator == "" {
		actuator = "Vibrate"
	}
	if scalar < 0 {
		scalar = 0
	}
	if scalar > 1 {
		scalar = 1
	}
	return c.Command(ctx, "ScalarCmd", map[string]any{
		"DeviceIndex": idx,
		"Scalars":     []map[string]any{{"Index": 0, "Scalar": scalar, "ActuatorType": actuator}},
	})
}

func (c *ButtplugClient) Linear(ctx context.Context, idx int, pos float64, durationMs int) error {
	if pos < 0 {
		pos = 0
	}
	if pos > 1 {
		pos = 1
	}
	if durationMs < 50 {
		durationMs = 50
	}
	return c.Command(ctx, "LinearCmd", map[string]any{
		"DeviceIndex": idx,
		"Vectors":     []map[string]any{{"Index": 0, "Duration": durationMs, "Position": pos}},
	})
}

func (c *ButtplugClient) SensorRead(ctx context.Context, idx, sensorIdx int) error {
	return c.Command(ctx, "SensorReadCmd", map[string]any{
		"DeviceIndex": idx,
		"SensorIndex": sensorIdx,
		"SensorType":  "Battery",
	})
}

func (c *ButtplugClient) readLoop(conn *websocket.Conn) {
	ctx := context.Background()
	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			c.ready.Store(false)
			c.mu.Lock()
			if c.conn == conn {
				c.conn = nil
				c.devices = map[int]*LiveDevice{}
				c.lastErr = err.Error()
			}
			fn := c.onChange
			c.mu.Unlock()
			if fn != nil {
				fn()
			}
			return
		}
		var arr []map[string]json.RawMessage
		if json.Unmarshal(data, &arr) != nil {
			continue
		}
		changed, removed := c.applyMessages(arr)
		if len(removed) > 0 {
			c.mu.Lock()
			rmFn := c.onRemoved
			c.mu.Unlock()
			if rmFn != nil {
				for _, id := range removed {
					rmFn(id)
				}
			}
		}
		if changed {
			c.mu.Lock()
			fn := c.onChange
			c.mu.Unlock()
			if fn != nil {
				fn()
			}
		}
	}
}

func (c *ButtplugClient) applyMessages(arr []map[string]json.RawMessage) (changed bool, removedIDs []string) {
	for _, m := range arr {
		if raw, ok := m["DeviceList"]; ok {
			var list struct {
				Devices []json.RawMessage `json:"Devices"`
			}
			if json.Unmarshal(raw, &list) == nil {
				// Funplay: never drop from DeviceList snapshots — only DeviceRemoved / session clear.
				for _, dr := range list.Devices {
					if c.upsertDeviceJSON(dr) {
						changed = true
					}
				}
			}
		}
		if raw, ok := m["DeviceAdded"]; ok {
			if c.upsertDeviceJSON(raw) {
				changed = true
			}
		}
		if raw, ok := m["DeviceRemoved"]; ok {
			var rem struct {
				DeviceIndex int `json:"DeviceIndex"`
			}
			if json.Unmarshal(raw, &rem) == nil {
				c.mu.Lock()
				if d, ok := c.devices[rem.DeviceIndex]; ok {
					removedIDs = append(removedIDs, d.DeviceID)
					delete(c.devices, rem.DeviceIndex)
					changed = true
				}
				c.mu.Unlock()
			}
		}
		if raw, ok := m["SensorReading"]; ok {
			var reading struct {
				DeviceIndex int    `json:"DeviceIndex"`
				SensorIndex int    `json:"SensorIndex"`
				SensorType  string `json:"SensorType"`
				Data        []int  `json:"Data"`
			}
			if json.Unmarshal(raw, &reading) == nil && strings.EqualFold(reading.SensorType, "Battery") && len(reading.Data) > 0 {
				c.mu.Lock()
				if d, ok := c.devices[reading.DeviceIndex]; ok {
					v := reading.Data[0]
					d.BatteryPercent = &v
					changed = true
				}
				c.mu.Unlock()
			}
		}
		if raw, ok := m["Error"]; ok {
			var errObj map[string]any
			if json.Unmarshal(raw, &errObj) == nil {
				msg, _ := errObj["ErrorMessage"].(string)
				if msg != "" && !strings.Contains(msg, "Ping") {
					c.mu.Lock()
					c.lastErr = msg
					c.mu.Unlock()
					slog.Warn("buttplug error", "msg", msg)
				}
			}
		}
	}
	return changed, removedIDs
}

func (c *ButtplugClient) upsertDeviceJSON(raw json.RawMessage) bool {
	var d struct {
		DeviceIndex    int            `json:"DeviceIndex"`
		DeviceName     string         `json:"DeviceName"`
		DeviceMessages map[string]any `json:"DeviceMessages"`
	}
	if json.Unmarshal(raw, &d) != nil {
		return false
	}
	name := strings.TrimSpace(d.DeviceName)
	if name == "" {
		name = fmt.Sprintf("Device %d", d.DeviceIndex)
	}
	kind := inferKind(d.DeviceMessages, name)
	id := StableDeviceID(name)
	live := &LiveDevice{
		Index:    d.DeviceIndex,
		DeviceID: id,
		Name:     name,
		Kind:     kind,
	}
	if idx := batterySensorIndex(d.DeviceMessages); idx >= 0 {
		live.BatterySensorIndex = &idx
	}
	c.mu.Lock()
	prev, existed := c.devices[d.DeviceIndex]
	if existed && prev.BatteryPercent != nil {
		live.BatteryPercent = prev.BatteryPercent
	}
	c.devices[d.DeviceIndex] = live
	c.mu.Unlock()
	return !existed || prev.Name != name || prev.Kind != kind
}

func inferKind(msgs map[string]any, name string) string {
	if IsVortexName(name) {
		return "constrict"
	}
	hasVibrate, hasConstrict, hasLinear := false, false, false
	if msgs == nil {
		return "scalar"
	}
	if _, ok := msgs["LinearCmd"]; ok {
		hasLinear = true
	}
	if attrs, ok := msgs["ScalarCmd"].([]any); ok {
		for _, a := range attrs {
			m, _ := a.(map[string]any)
			at, _ := m["ActuatorType"].(string)
			switch at {
			case "Vibrate":
				hasVibrate = true
			case "Constrict":
				hasConstrict = true
			case "Position":
				hasLinear = true
			}
		}
	}
	if hasLinear {
		return "linear"
	}
	if hasConstrict && !hasVibrate {
		return "constrict"
	}
	return "scalar"
}

func batterySensorIndex(msgs map[string]any) int {
	if msgs == nil {
		return -1
	}
	attrs, ok := msgs["SensorReadCmd"].([]any)
	if !ok {
		return -1
	}
	for i, a := range attrs {
		m, _ := a.(map[string]any)
		st, _ := m["SensorType"].(string)
		if strings.EqualFold(st, "Battery") {
			if idx, ok := m["Index"].(float64); ok {
				return int(idx)
			}
			return i
		}
	}
	return -1
}
