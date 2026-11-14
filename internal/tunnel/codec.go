package tunnel

import (
	"encoding/binary"
	"errors"
	"io"
)

// MaxPacket is the maximum encapsulated IP packet size.
const MaxPacket = 65535

// Encode writes a length-prefixed packet to w.
func Encode(w io.Writer, pkt []byte) error {
	if len(pkt) == 0 || len(pkt) > MaxPacket {
		return errors.New("invalid packet size")
	}
	var hdr [4]byte
	binary.BigEndian.PutUint32(hdr[:], uint32(len(pkt)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	_, err := w.Write(pkt)
	return err
}

// Decode reads a length-prefixed packet from r.
func Decode(r io.Reader) ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(hdr[:])
	if n == 0 || n > MaxPacket {
		return nil, errors.New("invalid packet length")
	}
	pkt := make([]byte, n)
	if _, err := io.ReadFull(r, pkt); err != nil {
		return nil, err
	}
	return pkt, nil
}

// ExtractDstVIP returns the destination virtual IP (bytes 16..20 of an IPv4
// header) as a uint32, for mesh routing.
func ExtractDstVIP(pkt []byte) (uint32, error) {
	if len(pkt) < 20 {
		return 0, errors.New("packet too short for IPv4 header")
	}
	if pkt[0]>>4 != 4 {
		return 0, errors.New("not an IPv4 packet")
	}
	return binary.BigEndian.Uint32(pkt[16:20]), nil
}
