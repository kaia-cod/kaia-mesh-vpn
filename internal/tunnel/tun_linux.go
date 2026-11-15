//go:build linux

package tunnel

import (
	"fmt"
	"sync"
	"syscall"
	"unsafe"
)

// ioctl constants for the TUN driver (linux/if_tun.h).
const (
	tunSetIff  = 0x400454ca
	iffTun     = 0x0001
	iffNoPi    = 0x1000
	ifNameSize = 16
)

// ifReq mirrors struct ifreq from <net/if.h>, large enough for the union.
type ifReq struct {
	Name  [ifNameSize]byte
	Flags uint16
	_     [22]byte
}

// TUN is an opened Linux layer-3 tunnel device.
//
// The raw file descriptor is kept instead of an *os.File on purpose: os.File
// registers the fd with Go's runtime network poller, which does not support
// character devices such as /dev/net/tun. Reading through os.File on such an
// fd fails with "not pollable" and kills the datapath. A raw descriptor in
// blocking mode is the only reliable option.
type TUN struct {
	fd      int
	name    string
	writeMu sync.Mutex
}

// OpenTUN opens (creating it if needed) a TUN interface called name.
func OpenTUN(name string) (*TUN, error) {
	// O_CLOEXEC only: no O_NONBLOCK, so reads block in the kernel and the Go
	// scheduler hands the thread back to other goroutines while waiting.
	fd, err := syscall.Open("/dev/net/tun", syscall.O_RDWR|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, fmt.Errorf("open /dev/net/tun: %w", err)
	}

	var req ifReq
	copy(req.Name[:ifNameSize-1], name)
	// IFF_TUN = layer 3 packets, IFF_NO_PI = no 4-byte packet information prefix.
	req.Flags = iffTun | iffNoPi

	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), uintptr(tunSetIff), uintptr(unsafe.Pointer(&req)))
	if errno != 0 {
		syscall.Close(fd)
		return nil, fmt.Errorf("TUNSETIFF %s: %w", name, errno)
	}

	// Defensive: force blocking mode even if the descriptor was inherited
	// non-blocking.
	forceBlocking(fd)

	actual := make([]byte, 0, ifNameSize)
	for _, c := range req.Name {
		if c == 0 {
			break
		}
		actual = append(actual, c)
	}
	return &TUN{fd: fd, name: string(actual)}, nil
}

// Name returns the real interface name assigned by the kernel.
func (t *TUN) Name() string { return t.name }

// forceBlocking clears O_NONBLOCK on fd if it is set. Android's
// ParcelFileDescriptor may hand over a non-blocking descriptor, which would
// make ReadPacket spin on EAGAIN instead of sleeping in the kernel.
func forceBlocking(fd int) {
	flags, _, errno := syscall.Syscall(syscall.SYS_FCNTL, uintptr(fd), uintptr(syscall.F_GETFL), 0)
	if errno == 0 && flags&uintptr(syscall.O_NONBLOCK) != 0 {
		syscall.Syscall(syscall.SYS_FCNTL, uintptr(fd), uintptr(syscall.F_SETFL), flags&^uintptr(syscall.O_NONBLOCK))
	}
}

// ReadPacket reads one IP packet from the interface into pkt.
func (t *TUN) ReadPacket(pkt []byte) (int, error) {
	for {
		n, err := syscall.Read(t.fd, pkt)
		if err == syscall.EINTR || err == syscall.EAGAIN || err == syscall.EWOULDBLOCK {
			continue
		}
		return n, err
	}
}

// WritePacket writes one IP packet to the interface.
func (t *TUN) WritePacket(pkt []byte) error {
	t.writeMu.Lock()
	defer t.writeMu.Unlock()
	for {
		n, err := syscall.Write(t.fd, pkt)
		if err == syscall.EINTR || err == syscall.EAGAIN || err == syscall.EWOULDBLOCK {
			continue
		}
		if err != nil {
			return err
		}
		if n != len(pkt) {
			return fmt.Errorf("tun: short write %d/%d", n, len(pkt))
		}
		return nil
	}
}

// Close closes the device.
func (t *TUN) Close() error {
	if t.fd < 0 {
		return nil
	}
	err := syscall.Close(t.fd)
	t.fd = -1
	return err
}
