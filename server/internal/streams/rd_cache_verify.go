package streams

import (
	"context"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Torrentio still stamps [RD+] from its own short-lived guess. Real-Debrid removed
// instantAvailability, so that badge is often stale. A resolve that is actually
// hosted redirects to *.download.real-debrid.com; an uncached one serves
// torrentio's downloading_*.mp4 placeholder.

func VerifyCachedFlags(ctx context.Context, token string, cands []Candidate) []Candidate {
	token = strings.TrimSpace(token)
	if token == "" || len(cands) == 0 {
		return cands
	}
	out := make([]Candidate, len(cands))
	copy(out, cands)

	idxs := make([]int, 0, len(out))
	for i := range out {
		if !out[i].Cached {
			continue
		}
		if resolveURLForProbe(token, out[i]) == "" {
			continue
		}
		idxs = append(idxs, i)
	}
	if len(idxs) == 0 {
		return out
	}

	client := &http.Client{
		Timeout: 12 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
	workers := 6
	if len(idxs) < workers {
		workers = len(idxs)
	}
	var wg sync.WaitGroup
	ch := make(chan int, len(idxs))
	for _, i := range idxs {
		ch <- i
	}
	close(ch)
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range ch {
				u := resolveURLForProbe(token, out[i])
				if u == "" {
					continue
				}
				if !rdResolveIsHosted(ctx, client, u) {
					out[i].Cached = false
					if strings.EqualFold(out[i].Source, "realdebrid") {
						out[i].Source = "torrent"
					}
				}
			}
		}()
	}
	wg.Wait()
	return out
}

func resolveURLForProbe(token string, c Candidate) string {
	if u := strings.TrimSpace(c.URL); strings.Contains(strings.ToLower(u), "torrentio.strem.fun/resolve/") {
		return u
	}
	if c.InfoHash == "" {
		return ""
	}
	return torrentioResolveURL(token, c.InfoHash, c.FileIndex, c.Filename)
}

func rdResolveIsHosted(ctx context.Context, client *http.Client, resolveURL string) bool {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, resolveURL, nil)
	if err != nil {
		return false
	}
	req.Header.Set("User-Agent", "coog/0.1")
	req.Header.Set("Range", "bytes=0-0")
	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	final := ""
	if resp.Request != nil && resp.Request.URL != nil {
		final = resp.Request.URL.String()
	}
	if loc := resp.Header.Get("Location"); loc != "" {
		final = loc
	}
	return isRealDebridHostedURL(final)
}

func isRealDebridHostedURL(raw string) bool {
	u := strings.ToLower(strings.TrimSpace(raw))
	if u == "" {
		return false
	}
	if strings.Contains(u, "torrentio.strem.fun/videos/downloading") {
		return false
	}
	return strings.Contains(u, "download.real-debrid.com") ||
		strings.Contains(u, "real-debrid.com/d/")
}
