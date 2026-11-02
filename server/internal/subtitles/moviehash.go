package subtitles

import (
	"encoding/binary"
	"fmt"
	"io"
	"os"
)

const osHashChunk = 64 * 1024

// MovieHash computes the OpenSubtitles movie hash for a local video file.
// Empty string means the file is unreadable or too small.
func MovieHash(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		return ""
	}
	size := st.Size()
	if size < osHashChunk {
		return ""
	}
	var hash uint64 = uint64(size)

	head := make([]byte, osHashChunk)
	if _, err := io.ReadFull(f, head); err != nil {
		return ""
	}
	hash = addHashChunk(hash, head)

	if _, err := f.Seek(size-osHashChunk, io.SeekStart); err != nil {
		return ""
	}
	tail := make([]byte, osHashChunk)
	if _, err := io.ReadFull(f, tail); err != nil {
		return ""
	}
	hash = addHashChunk(hash, tail)
	return fmt.Sprintf("%016x", hash)
}

func addHashChunk(hash uint64, buf []byte) uint64 {
	for i := 0; i+8 <= len(buf); i += 8 {
		hash += binary.LittleEndian.Uint64(buf[i : i+8])
	}
	return hash
}
