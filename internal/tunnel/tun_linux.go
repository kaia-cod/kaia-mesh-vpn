//go:build linux

package tunnel

import (
	"fmt"
	"os"
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
type TUN struct {
	file *os.File
	name string
}

// OpenTUN opens (creating it if needed) a TUN interface called name.
func OpenTUN(name string) (*TUN, error) {
	f, err := os.OpenFile("/dev/net/tun", os.O_RDWR|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, fmt.Errorf("open /dev/net/tun: %w", err)
	}

	var req ifReq
	copy(req.Name[:ifNameSize-1], name)
	// IFF_TUN = layer 3 packets, IFF_NO_PI = no 4-byte packet information prefix.
	req.Flags = iffTun | iffNoPi

	fd := int(f.Fd())
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), uintptr(tunSetIff), uintptr(unsafe.Pointer(&req)))
	if errno != 0 {
		f.Close()
		return nil, fmt.Errorf("TUNSETIFF %s: %w", name, errno)
	}

	actual := string(req.Name[:])
	for i, c := range actual {
		if c == 0 {
			actual = actual[:i]
			break
		}
	}
	return &TUN{file: f, name: actual}, nil
}

// Name returns the real interface name assigned by the kernel.
func (t *TUN) Name() string { return t.name }

// ReadPacket reads one IP packet from the interface into pkt.
func (t *TUN) ReadPacket(pkt []byte) (int, error) {
	return t.file.Read(pkt)
}

// WritePacket writes one IP packet to the interface.
func (t *TUN) WritePacket(pkt []byte) (int, error) {
	return t.file.Write(pkt)
}

// Close closes the device.
func (t *TUN) Close() error { return t.file.Close() }
