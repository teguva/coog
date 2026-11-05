package api

import (
	"context"
	"time"

	"coog/internal/meta"
)

const (
	// Browse pages enqueue a full grid of posters; keep a few workers ahead of hold-scroll.
	artWarmWorkers   = 4
	artWarmQueueSize = 512
)

type artWarmJob struct {
	item meta.CatalogItem
}

// startArtWarmQueue runs a bounded worker pool that derives catalog thumbs off the request path.
func (s *Server) startArtWarmQueue(ctx context.Context) {
	s.artWarmOnce.Do(func() {
		s.artWarmCh = make(chan artWarmJob, artWarmQueueSize)
		s.artWarmSeen = map[string]time.Time{}
		for i := 0; i < artWarmWorkers; i++ {
			go s.artWarmWorker(ctx)
		}
	})
}

func (s *Server) artWarmWorker(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case job, ok := <-s.artWarmCh:
			if !ok {
				return
			}
			key := meta.CatalogArtKeyForItem(job.item)
			if key == "" {
				continue
			}
			bg, cancel := context.WithTimeout(context.Background(), 45*time.Second)
			s.meta.WarmCatalogArt(bg, job.item)
			cancel()
		}
	}
}

// enqueueCatalogArtWarm queues missing thumbs for the given items (non-blocking).
func (s *Server) enqueueCatalogArtWarm(items []meta.CatalogItem) {
	if s.meta == nil || len(items) == 0 || s.artWarmCh == nil {
		return
	}
	now := time.Now()
	for _, item := range items {
		key := meta.CatalogArtKeyForItem(item)
		if key == "" {
			continue
		}
		if s.meta.HasCatalogArt(key, "poster", meta.ArtSizeThumb) &&
			s.meta.HasCatalogArt(key, "backdrop", meta.ArtSizeThumb) {
			continue
		}
		s.artWarmMu.Lock()
		if t, ok := s.artWarmSeen[key]; ok && now.Sub(t) < 10*time.Minute {
			s.artWarmMu.Unlock()
			continue
		}
		s.artWarmSeen[key] = now
		// Bound map growth.
		if len(s.artWarmSeen) > 2000 {
			for k, at := range s.artWarmSeen {
				if now.Sub(at) > 30*time.Minute {
					delete(s.artWarmSeen, k)
				}
			}
		}
		s.artWarmMu.Unlock()
		select {
		case s.artWarmCh <- artWarmJob{item: item}:
		default:
			// Queue full — scheduled warm will catch up.
			return
		}
	}
}
