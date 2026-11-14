package peering

import (
	"testing"

	peer "github.com/libp2p/go-libp2p/core/peer"
)

func TestTable(t *testing.T) {
	tb := NewTable()
	id := peer.ID("QmTest")
	tb.Upsert(Entry{VirtualIP: 0x0A2A0001, PeerID: id})
	if e, ok := tb.Lookup(0x0A2A0001); !ok || e.PeerID != id {
		t.Fatal("lookup")
	}
	tb.Remove(id)
	if tb.Len() != 0 {
		t.Fatal("remove")
	}
}
