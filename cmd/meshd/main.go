// Command meshd is the Kaia Mesh VPN daemon.
package main

import (
	"context"
	"crypto/ed25519"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"

	pubsub "github.com/libp2p/go-libp2p-pubsub"

	"github.com/kaia-cod/kaia-mesh-vpn/internal/config"
	"github.com/kaia-cod/kaia-mesh-vpn/internal/node"
	"github.com/kaia-cod/kaia-mesh-vpn/internal/peering"
)

func main() {
	var (
		virtualIP  = flag.String("vip", "10.42.0.1", "virtual IP on the mesh (10.42.x.y)")
		bootstrap  = flag.String("bootstrap", "", "comma-separated bootstrap multiaddrs")
		seedFile   = flag.String("identity", "", "path to identity seed file (created if missing)")
		socketPath = flag.String("socket", "", "Unix socket for local apps (Android/Windows)")
	)
	flag.Parse()

	vip, err := config.ParseVirtualIP(*virtualIP)
	if err != nil {
		log.Fatalf("virtual IP: %v", err)
	}

	cfg := config.Default()
	cfg.VirtualIP = net.IPv4(byte(vip>>24), byte(vip>>16), byte(vip>>8), byte(vip))
	if *bootstrap != "" {
		cfg.BootstrapPeers = split(*bootstrap)
	}
	if *seedFile != "" {
		cfg.IdentitySeed = loadOrCreateSeed(*seedFile)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	h, err := node.New(ctx, cfg)
	if err != nil {
		log.Fatalf("node: %v", err)
	}
	defer h.Close()
	fmt.Printf("meshd ready: PeerID=%s virtualIP=%s\n", h.ID(), cfg.VirtualIP)
	for _, a := range h.Addrs() {
		fmt.Printf("listen: %s/p2p/%s\n", a, h.ID())
	}

	ps, err := pubsub.NewGossipSub(ctx, h)
	if err != nil {
		log.Fatalf("pubsub: %v", err)
	}
	topic, err := ps.Join(cfg.AnnounceTopic)
	if err != nil {
		log.Fatalf("topic: %v", err)
	}
	table := peering.NewTable()
	table.Upsert(peering.Entry{VirtualIP: vip, PeerID: h.ID()})

	announce := fmt.Sprintf("vip=%s peer=%s", cfg.VirtualIP, h.ID())
	_ = topic.Publish(ctx, []byte(announce))

	fmt.Printf("announced on %s, socket=%s\n", cfg.AnnounceTopic, *socketPath)
	<-ctx.Done()
}

func split(s string) []string {
	var out []string
	var cur string
	for _, c := range s {
		if c == ',' {
			out = append(out, cur)
			cur = ""
		} else {
			cur += string(c)
		}
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

func loadOrCreateSeed(path string) []byte {
	if b, err := os.ReadFile(path); err == nil && len(b) == ed25519.SeedSize {
		return b
	}
	seed, err := config.NewIdentity()
	if err != nil {
		log.Fatalf("identity: %v", err)
	}
	_ = os.WriteFile(path, seed, 0o600)
	return seed
}
