package peering

import (
	"context"
	"fmt"
	"log"
	"strings"

	pubsub "github.com/libp2p/go-libp2p-pubsub"
	libp2ppeer "github.com/libp2p/go-libp2p/core/peer"

	"github.com/kaia-cod/kaia-mesh-vpn/internal/config"
)

// Announcement is the wire format published on the announce topic:
//
//	vip=10.42.0.1 peer=12D3KooW...
type Announcement struct {
	VirtualIP uint32
	PeerID    libp2ppeer.ID
}

// Format renders an announcement for publication.
//
// PeerID is a binary multihash stored in a Go string: it must be rendered with
// its String() method (canonical base58 "12D3KooW…" form) and never with %s on
// the raw value, otherwise peers receive an unparseable base32 CID.
func (a Announcement) Format() string {
	return fmt.Sprintf("vip=%s peer=%s", config.EncodeVirtualIP(a.VirtualIP), a.PeerID.String())
}

// ParseAnnouncement decodes a published string. Unknown fields are ignored so
// that future versions can extend the format without breaking older nodes.
func ParseAnnouncement(s string) (Announcement, error) {
	var a Announcement
	for _, field := range strings.Fields(s) {
		k, v, ok := strings.Cut(field, "=")
		if !ok {
			continue
		}
		switch k {
		case "vip":
			vip, err := config.ParseVirtualIP(v)
			if err != nil {
				return a, fmt.Errorf("bad vip %q: %w", v, err)
			}
			a.VirtualIP = vip
		case "peer":
			id, err := libp2ppeer.Decode(v)
			if err != nil {
				return a, fmt.Errorf("bad peer %q: %w", v, err)
			}
			a.PeerID = id
		}
	}
	if a.PeerID == "" {
		return a, fmt.Errorf("announcement without peer id")
	}
	return a, nil
}

// SubscribeToAnnouncements feeds table from the pubsub announce topic until
// ctx is cancelled. It returns immediately; the loop runs in a goroutine.
func SubscribeToAnnouncements(ctx context.Context, topic *pubsub.Topic, table *Table) {
	sub, err := topic.Subscribe()
	if err != nil {
		log.Printf("peering: subscribe: %v", err)
		return
	}
	go func() {
		defer sub.Cancel()
		for {
			msg, err := sub.Next(ctx)
			if err != nil {
				if ctx.Err() == nil {
					log.Printf("peering: next: %v", err)
				}
				return
			}
			if msg.GetFrom() == "" {
				continue
			}
			a, err := ParseAnnouncement(string(msg.Data))
			if err != nil {
				continue
			}
			table.Upsert(Entry{VirtualIP: a.VirtualIP, PeerID: a.PeerID})
		}
	}()
}
