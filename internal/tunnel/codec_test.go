package tunnel

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestCodecRoundTrip(t *testing.T) {
	pkt := make([]byte, 20)
	pkt[0] = 0x45
	binary.BigEndian.PutUint32(pkt[16:20], 0x0A2A0709)
	var buf bytes.Buffer
	if err := Encode(&buf, pkt); err != nil {
		t.Fatal(err)
	}
	out, err := Decode(&buf)
	if err != nil {
		t.Fatal(err)
	}
	vip, err := ExtractDstVIP(out)
	if err != nil {
		t.Fatal(err)
	}
	if vip != 0x0A2A0709 {
		t.Fatalf("dst vip %08x", vip)
	}
}
