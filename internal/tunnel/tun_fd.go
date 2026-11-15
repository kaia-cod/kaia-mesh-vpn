//go:build linux

package tunnel

import "fmt"

// OpenTUNFromFD wraps an already-established TUN file descriptor.
//
// On Android the VpnService (Java) is the only component allowed to create the
// TUN device: it calls Builder.establish() and hands us a ParcelFileDescriptor.
// The Android host code therefore passes that descriptor number to meshd with
// -tunfd, and meshd adopts it here instead of touching /dev/net/tun
// (which does not exist on Android).
//
// The descriptor is adopted as a raw int (never kept inside an *os.File):
// os.File registers the fd with Go's runtime network poller, which refuses
// character devices such as a tun fd and fails with "not pollable", killing the
// datapath. TUN therefore only ever holds a raw fd, exactly like OpenTUN does
// on regular Linux hosts.
func OpenTUNFromFD(fd uintptr, name string) (*TUN, error) {
	if fd == 0 {
		return nil, fmt.Errorf("tunfd: invalid descriptor 0")
	}
	if name == "" {
		name = "tun-fd"
	}

	raw := int(fd)
	// Defensive: force blocking mode even if the descriptor was inherited
	// non-blocking (some OEM ParcelFileDescriptor implementations set
	// O_NONBLOCK), so ReadPacket blocks in the kernel instead of busy-looping.
	forceBlocking(raw)

	// We own the descriptor: TUN.Close() will close it.
	return &TUN{fd: raw, name: name}, nil
}
