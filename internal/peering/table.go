package peering

import (
	"sync"

	peer "github.com/libp2p/go-libp2p/core/peer"
)

// Entry maps a virtual IP to a peer.
type Entry struct {
	VirtualIP uint32
	PeerID    peer.ID
	PubKey    []byte
}

// Table is a concurrent virtual-IP -> PeerID routing table built from
// mesh announcements (PubSub + DHT).
type Table struct {
	mu      sync.RWMutex
	entries map[uint32]Entry
	byPeer  map[peer.ID]Entry
}

// NewTable creates an empty table.
func NewTable() *Table {
	return &Table{entries: make(map[uint32]Entry), byPeer: make(map[peer.ID]Entry)}
}

// Upsert inserts or updates an entry.
func (t *Table) Upsert(e Entry) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.entries[e.VirtualIP] = e
	t.byPeer[e.PeerID] = e
}

// Lookup returns the entry for a virtual IP.
func (t *Table) Lookup(vip uint32) (Entry, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	e, ok := t.entries[vip]
	return e, ok
}

// LookupPeer returns the entry for a peer.
func (t *Table) LookupPeer(id peer.ID) (Entry, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	e, ok := t.byPeer[id]
	return e, ok
}

// Len returns the number of known peers.
func (t *Table) Len() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return len(t.entries)
}

// Remove deletes a peer and its virtual IP.
func (t *Table) Remove(id peer.ID) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if e, ok := t.byPeer[id]; ok {
		delete(t.entries, e.VirtualIP)
	}
	delete(t.byPeer, id)
}
