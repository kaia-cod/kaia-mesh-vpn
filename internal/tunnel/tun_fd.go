//go:build linux || android

package tunnel

import (
	"fmt"
	"os"
)

// OpenTUNFromFD wraps an already-established TUN file descriptor.
//
// On Android the VpnService (Java) is the only component allowed to create the
// TUN device: it calls Builder.establish() and hands us a ParcelFileDescriptor.
// The Android host code therefore passes that descriptor number to meshd with
// -tunfd, and meshd adopts it here instead of touching /dev/net/tun
// (which does not exist on Android).
func OpenTUNFromFD(fd uintptr, name string) (*TUN, error) {
	if fd == 0 {
		return nil, fmt.Errorf("tunfd: invalid descriptor 0")
	}
	f := os.NewFile(fd, "tun-fd")
	if f == nil {
		return nil, fmt.Errorf("tunfd: cannot wrap descriptor %d", fd)
	}
	if name == "" {
		name = "tun-fd"
	}
	return &TUN{file: f, name: name}, nil
}
