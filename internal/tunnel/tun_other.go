//go:build !linux

package tunnel

import "errors"

// TUN is unavailable on this platform (Android/Windows have their own VpnService/Wintun).
type TUN struct{ name string }

// OpenTUN always fails off Linux: the daemon datapath targets Linux hosts.
func OpenTUN(name string) (*TUN, error) {
	return nil, errors.New("tun: only supported on linux")
}

func (t *TUN) Name() string                       { return t.name }
func (t *TUN) ReadPacket(pkt []byte) (int, error) { return 0, errors.New("tun: unsupported") }
func (t *TUN) WritePacket(pkt []byte) error       { return errors.New("tun: unsupported") }
func (t *TUN) Close() error                       { return nil }

// OpenTUNFromFD is the non-Linux counterpart of the Android/Linux helper.
//
// On Android the VpnService hands meshd a TUN file descriptor via -tunfd. That
// code path (and the raw-descriptor adoption it relies on) only exists in
// tun_fd.go, which is built for linux only. This stub keeps the package
// compilable on darwin/windows/freebsd (go vet / go build must pass there) and
// fails loudly at runtime instead of silently pretending to work.
func OpenTUNFromFD(fd uintptr, name string) (*TUN, error) {
	return nil, errors.New("tunfd: adopting an existing TUN descriptor is only supported on android/linux")
}
