// Package node builds the libp2p host for a Kaia Mesh node.
package node

import (
	"context"
	"crypto/ed25519"
	"fmt"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	libp2ppeer "github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/p2p/discovery/mdns"
	"github.com/libp2p/go-libp2p/p2p/net/connmgr"
	"github.com/libp2p/go-libp2p/p2p/security/noise"
	tls "github.com/libp2p/go-libp2p/p2p/security/tls"

	"github.com/kaia-cod/kaia-mesh-vpn/internal/config"
)

// New creates a libp2p host configured for the mesh.
func New(ctx context.Context, cfg config.Config) (host.Host, error) {
	cm, err := connmgr.NewConnManager(16, 64)
	if err != nil {
		return nil, fmt.Errorf("connmgr: %w", err)
	}
	opts := []libp2p.Option{
		libp2p.ListenAddrStrings(cfg.ListenAddrs...),
		// Security() takes constructors, not protocol names.
		libp2p.Security(noise.ID, noise.New),
		libp2p.Security(tls.ID, tls.New),
		libp2p.EnableNATService(),
		libp2p.EnableHolePunching(),
		libp2p.ConnectionManager(cm),
	}

	key, err := identityKey(cfg.IdentitySeed)
	if err != nil {
		return nil, fmt.Errorf("identity: %w", err)
	}
	opts = append(opts, libp2p.Identity(key))

	h, err := libp2p.New(opts...)
	if err != nil {
		return nil, fmt.Errorf("host: %w", err)
	}

	for _, addr := range cfg.BootstrapPeers {
		if ai, err := libp2ppeer.AddrInfoFromString(addr); err == nil {
			_ = h.Connect(ctx, *ai)
		}
	}

	mdns.NewMdnsService(h, "kaia-mesh", &mdnsPeerDiscover{Host: h})
	return h, nil
}

// identityKey derives a stable Ed25519 key from the configured seed.
// An empty seed yields a fresh ephemeral key.
func identityKey(seed []byte) (crypto.PrivKey, error) {
	if len(seed) != ed25519.SeedSize {
		if len(seed) != 0 {
			return nil, fmt.Errorf("identity seed must be %d bytes, got %d", ed25519.SeedSize, len(seed))
		}
		key, _, err := crypto.GenerateEd25519Key(nil)
		return key, err
	}
	return crypto.UnmarshalEd25519PrivateKey(ed25519.NewKeyFromSeed(seed))
}

type mdnsPeerDiscover struct {
	Host host.Host
}

func (d *mdnsPeerDiscover) HandlePeerFound(info libp2ppeer.AddrInfo) {
	_ = d.Host.Connect(context.Background(), info)
}
