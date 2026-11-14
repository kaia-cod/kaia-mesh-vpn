//go:build !linux

package tunnel

import "errors"

// TUN is unavailable on this platform (Android/Windows have their own VpnService/Wintun).
type TUN struct{ name string }

// OpenTUN always fails off Linux: the daemon datapath targets Linux hosts.
func OpenTUN(name string) (*TUN, error) {
	return nil, errors.New("tun: only supported on linux")
}

func (t *TUN) Name() string                        { return t.name }
func (t *TUN) ReadPacket(pkt []byte) (int, error)  { return 0, errors.New("tun: unsupported") }
func (t *TUN) WritePacket(pkt []byte) error       { return errors.New("tun: unsupported") }
func (t *TUN) Close() error                        { return nil }
