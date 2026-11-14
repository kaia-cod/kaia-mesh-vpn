package config

import "testing"

func TestParseVirtualIP(t *testing.T) {
	v, err := ParseVirtualIP("10.42.7.9")
	if err != nil {
		t.Fatal(err)
	}
	if got := EncodeVirtualIP(v); got != "10.42.7.9" {
		t.Fatalf("round trip: %s", got)
	}
}

func TestDefaultValid(t *testing.T) {
	cfg := Default()
	cfg.VirtualIP = []byte{10, 42, 0, 1}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
}
