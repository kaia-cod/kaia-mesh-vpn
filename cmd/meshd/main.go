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
	"runtime"
	"strings"
	"syscall"
	"time"

	pubsub "github.com/libp2p/go-libp2p-pubsub"
	"github.com/libp2p/go-libp2p/core/host"
	libp2ppeer "github.com/libp2p/go-libp2p/core/peer"

	"github.com/kaia-cod/kaia-mesh-vpn/internal/config"
	"github.com/kaia-cod/kaia-mesh-vpn/internal/node"
	"github.com/kaia-cod/kaia-mesh-vpn/internal/peering"
	"github.com/kaia-cod/kaia-mesh-vpn/internal/tunnel"
)

// version is bumped with the Android app; `meshd --version` prints it together
// with the build target so on-device tests can prove the binary ABI.
const version = "0.2.0"

func main() {
	var (
		virtualIP  = flag.String("vip", "10.42.0.1", "virtual IP on the mesh (10.42.x.y)")
		bootstrap  = flag.String("bootstrap", "", "comma-separated bootstrap multiaddrs")
		seedFile   = flag.String("identity", "", "path to identity seed file (created if missing)")
		socketPath = flag.String("socket", "", "Unix socket for local apps (Android/Windows)")
		connect    = flag.String("connect", "", "comma-separated peer multiaddrs to dial explicitly at startup")
		runFor     = flag.Duration("for", 0, "exit after this duration instead of running until SIGINT/SIGTERM (0 = forever)")
		ifaceName  = flag.String("iface", "kaiamesh", "TUN interface name (empty = no datapath, control plane only)")
		tunFD      = flag.Uint("tunfd", 0, "adopt an already-open TUN file descriptor (Android VpnService, 0 = create one via -iface)")
		showVer    = flag.Bool("version", false, "print version and build target, then exit 0")
	)
	flag.Parse()

	// Fast, side-effect-free probe: the Android instrumented test executes the
	// binary extracted into nativeLibraryDir with this flag to prove the exec
	// bit and the ABI actually work on the device (no network, no TUN).
	if *showVer {
		fmt.Printf("meshd %s (%s/%s, %s)\n",
			version, runtime.GOOS, runtime.GOARCH, runtime.Version())
		return
	}

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

	// Feed the routing table from mesh announcements (real peers -> VIPs).
	peering.SubscribeToAnnouncements(ctx, topic, table)

	announce := peering.Announcement{VirtualIP: vip, PeerID: h.ID()}
	// Publish repeatedly: GossipSub only delivers to peers that are already
	// connected and subscribed, and peers join the topic after us. A single
	// one-shot publish at startup is invisible to every later peer.
	go func() {
		tick := time.NewTicker(2 * time.Second)
		defer tick.Stop()
		for {
			if err := topic.Publish(ctx, []byte(announce.Format())); err != nil && ctx.Err() == nil {
				log.Printf("peering: announce: %v", err)
			}
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
			}
		}
	}()

	// Bring up the data path: TUN device <-> libp2p streams.
	// Two ways to obtain the TUN: Android hands us the descriptor created by
	// VpnService.establish() (-tunfd), otherwise we open /dev/net/tun (-iface).
	if *tunFD != 0 || *ifaceName != "" {
		var tunDev *tunnel.TUN
		var err error
		if *tunFD != 0 {
			tunDev, err = tunnel.OpenTUNFromFD(uintptr(*tunFD), *ifaceName)
		} else {
			tunDev, err = tunnel.OpenTUN(*ifaceName)
		}
		if err != nil {
			log.Printf("datapath disabled: %v", err)
		} else {
			dp := tunnel.NewDataPath(h, tunDev, table, cfg.Mtu)
			dp.Start()
			defer dp.Close()
			fmt.Printf("datapath up: iface=%s vip=%s mtu=%d\n", tunDev.Name(), cfg.VirtualIP, cfg.Mtu)
		}
	}

	fmt.Printf("announced on %s, socket=%s\n", cfg.AnnounceTopic, *socketPath)

	// Explicit peer dialing: the only reliable way to prove two nodes really
	// talk to each other, regardless of mDNS/DHT discovery.
	if *connect != "" {
		dialPeers(ctx, h, split(*connect))
	}

	if *runFor > 0 {
		timer := time.NewTimer(*runFor)
		defer timer.Stop()
		select {
		case <-ctx.Done():
		case <-timer.C:
			fmt.Println("run window elapsed, shutting down")
		}
		return
	}
	<-ctx.Done()
}

// dialPeers connects to every multiaddr given and reports the outcome.
// It prints a machine-readable CONNECTED / DIAL-FAILED line so scripts and
// operators can assert on the result.
func dialPeers(ctx context.Context, h host.Host, addrs []string) {
	for _, raw := range addrs {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		ai, err := libp2ppeer.AddrInfoFromString(raw)
		if err != nil {
			fmt.Printf("DIAL-FAILED %s: bad multiaddr: %v\n", raw, err)
			continue
		}
		dctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		err = h.Connect(dctx, *ai)
		cancel()
		if err != nil {
			fmt.Printf("DIAL-FAILED %s: %v\n", ai.ID, err)
			continue
		}
		fmt.Printf("CONNECTED peer=%s addr=%s\n", ai.ID, raw)
	}
	// Re-read the live connection table rather than trusting the dial call.
	conns := h.Network().Conns()
	fmt.Printf("PEERS count=%d\n", len(h.Network().Peers()))
	for _, c := range conns {
		fmt.Printf("LINK peer=%s remote=%s\n", c.RemotePeer(), c.RemoteMultiaddr())
	}
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
