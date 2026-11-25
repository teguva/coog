package interactive

import (
	"testing"
	"time"
)

func TestExtendSameDirectionMergesStaircase(t *testing.T) {
	acts := []Action{
		{At: 0, Pos: 90},
		{At: 100, Pos: 70},
		{At: 200, Pos: 40},
		{At: 300, Pos: 80}, // reverse
	}
	end := ExtendSameDirection(acts, 0)
	if end != 2 {
		t.Fatalf("expected merge to index 2, got %d", end)
	}
}

func TestAdaptMergedWithdrawal(t *testing.T) {
	st, ok := AdaptPowerBlowStroke(0, 90, 200, 40, false, 1, 1, 0, false, true)
	if !ok || !st.Suck {
		t.Fatalf("expected suck on merged withdrawal, ok=%v", ok)
	}
	if st.Duration != 200*time.Millisecond {
		t.Fatalf("dur=%v", st.Duration)
	}
	// 50 pos delta → level 0.5
	if st.Level < 0.45 || st.Level > 0.55 {
		t.Fatalf("merged amp level=%v want ~0.5", st.Level)
	}
}

func TestAdaptPowerBlowStrokeSuckOnWithdrawal(t *testing.T) {
	st, ok := AdaptPowerBlowStroke(0, 90, 200, 10, false, 1, 1, 0, false, true)
	if !ok || !st.Suck {
		t.Fatalf("expected suction on withdrawal, ok=%v suck=%v", ok, st.Suck)
	}
}

func TestAdaptPowerBlowStrokeReleaseOnInsertion(t *testing.T) {
	st, ok := AdaptPowerBlowStroke(0, 10, 180, 90, false, 1, 1, 0, false, true)
	if !ok || st.Suck {
		t.Fatalf("expected release on insertion, ok=%v suck=%v", ok, st.Suck)
	}
}

func TestAdaptPowerBlowStrokeNoRePumpWhileHolding(t *testing.T) {
	_, ok := AdaptPowerBlowStroke(0, 90, 200, 10, false, 1, 1, 0, true, true)
	if ok {
		t.Fatal("must not re-pump while holding")
	}
}

func TestAdaptPowerBlowStrokeSkipTinyWithdrawal(t *testing.T) {
	_, ok := AdaptPowerBlowStroke(0, 50, 200, 46, false, 1, 1, 0, false, true)
	if ok {
		t.Fatal("tiny withdrawal should not pump")
	}
}

func TestAdaptPowerBlowStrokeTinyVentWhileHolding(t *testing.T) {
	st, ok := AdaptPowerBlowStroke(0, 40, 80, 44, false, 1, 1, 0, true, true)
	if !ok || st.Suck {
		t.Fatalf("holding + small insertion should vent, ok=%v suck=%v", ok, st.Suck)
	}
}
