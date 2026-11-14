package config

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"net"
)

// Config describes a Kaia Mesh node.
type Config struct {
	ListenAddrs    []string
	BootstrapPeers []string
	VirtualIP      net.IP
	AnnounceTopic  string
	TunnelProto    string
	PeerProto      string
	Mtu            int
	IdentitySeed   []byte
}

const (
	AnnounceTopic = "kaia-mesh/announce/1.0.0"
	TunnelProto   = "/kaia-mesh/1.0.0"
	PeerProto     = "/kaia-mesh/peer/1.0.0"
)

// Default returns a sane default configuration.
func Default() Config {
	return Config{
		ListenAddrs:   []string{"/ip4/0.0.0.0/tcp/0", "/ip4/0.0.0.0/udp/0/quic"},
		AnnounceTopic: AnnounceTopic,
		TunnelProto:   TunnelProto,
		PeerProto:     PeerProto,
		Mtu:           1400,
	}
}

// Validate checks the configuration.
func (c Config) Validate() error {
	if c.VirtualIP == nil {
		return fmt.Errorf("virtual IP is required")
	}
	if c.AnnounceTopic == "" || c.TunnelProto == "" {
		return fmt.Errorf("protocol identifiers are required")
	}
	if c.Mtu <= 0 || c.Mtu > 65535 {
		return fmt.Errorf("invalid MTU %d", c.Mtu)
	}
	return nil
}

// ParseVirtualIP converts an IPv4 address (e.g. 10.42.7.9) to a 4-byte CID.
func ParseVirtualIP(s string) (uint32, error) {
	ip := net.ParseIP(s).To4()
	if ip == nil {
		return 0, fmt.Errorf("not an IPv4 address: %s", s)
	}
	return binary.BigEndian.Uint32(ip), nil
}

// EncodeVirtualIP is the inverse of ParseVirtualIP.
func EncodeVirtualIP(v uint32) string {
	return net.IPv4(byte(v>>24), byte(v>>16), byte(v>>8), byte(v)).String()
}

// NewIdentity generates an Ed25519 seed for the node's PeerID.
func NewIdentity() ([]byte, error) {
	seed := make([]byte, ed25519.SeedSize)
	if _, err := rand.Read(seed); err != nil {
		return nil, err
	}
	return seed, nil
}
