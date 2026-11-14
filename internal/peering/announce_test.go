package peering

import (
	"strings"
	"testing"

	libp2ppeer "github.com/libp2p/go-libp2p/core/peer"

	"github.com/kaia-cod/kaia-mesh-vpn/internal/config"
)

const testPeerIDStr = "12D3KooWGRZBVSL8EZWAMDCc11e4g5qVT4SyeBS3SsCm6N1VSW6c"

func TestAnnouncementRoundTrip(t *testing.T) {
	vip, err := config.ParseVirtualIP("10.42.0.7")
	if err != nil {
		t.Fatalf("vip: %v", err)
	}
	// A real peer.ID is a binary multihash: it must be built by decoding the
	// canonical string form, never by a string literal cast.
	pid, err := libp2ppeer.Decode(testPeerIDStr)
	if err != nil {
		t.Fatalf("decode test peer id: %v", err)
	}
	a := Announcement{VirtualIP: vip, PeerID: pid}
	wire := a.Format()
	got, err := ParseAnnouncement(wire)
	if err != nil {
		t.Fatalf("parse %q: %v", wire, err)
	}
	if got.VirtualIP != a.VirtualIP || got.PeerID != a.PeerID {
		t.Fatalf("round trip mismatch: %+v vs %+v", got, a)
	}
}

// Regression: Format must emit the canonical base58 "12D3KooW…" peer id.
// Before the fix it used %s on the raw binary value, producing a base32 CID
// (e.g. "CovLVG4f…") that ParseAnnouncement rejects — so no peer could ever
// parse another peer's announcement.
func TestFormatEmitsCanonicalPeerID(t *testing.T) {
	pid, err := libp2ppeer.Decode(testPeerIDStr)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	vip, _ := config.ParseVirtualIP("10.42.0.9")
	wire := Announcement{VirtualIP: vip, PeerID: pid}.Format()
	if !strings.Contains(wire, "peer="+testPeerIDStr) {
		t.Fatalf("wire %q does not carry canonical peer id %q", wire, testPeerIDStr)
	}
}

func TestParseAnnouncementRejectsGarbage(t *testing.T) {
	if _, err := ParseAnnouncement("hello world"); err == nil {
		t.Fatal("expected error for announcement without peer")
	}
}
