package tunnel

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	libp2ppeer "github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"

	"github.com/kaia-cod/kaia-mesh-vpn/internal/peering"
)

// VPNProtocol is the libp2p stream protocol carrying encapsulated IP packets.
const VPNProtocol = protocol.ID("/kaia-mesh/1.0.0")

// Router resolves a destination virtual IP to the peer that owns it.
type Router interface {
	Lookup(vip uint32) (peering.Entry, bool)
}

// DataPath moves IP packets between a TUN device and libp2p streams.
//
// Outbound: TUN -> ExtractDstVIP -> Router.Lookup -> Encode -> stream to peer.
// Inbound : stream -> Decode -> TUN.
type DataPath struct {
	host    host.Host
	tun     *TUN
	router  Router
	mtu     int
	ctx     context.Context
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	mu      sync.Mutex
	streams map[libp2ppeer.ID]*bufio.Writer // per-peer outbound stream
	stats   Stats
}

// Stats counts packets handled by the datapath (for logs and proofs).
type Stats struct {
	mu        sync.Mutex
	FromTUN   uint64
	ToTUN     uint64
	NoRoute   uint64
	SendError uint64
}

func (s *Stats) inc(f func(*Stats)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f(s)
}

// Snapshot returns a copy of the counters.
func (s *Stats) Snapshot() Stats {
	s.mu.Lock()
	defer s.mu.Unlock()
	return Stats{FromTUN: s.FromTUN, ToTUN: s.ToTUN, NoRoute: s.NoRoute, SendError: s.SendError}
}

// NewDataPath builds a datapath bound to a TUN device and a routing table.
func NewDataPath(h host.Host, t *TUN, r Router, mtu int) *DataPath {
	ctx, cancel := context.WithCancel(context.Background())
	if mtu <= 0 {
		mtu = 1400
	}
	return &DataPath{
		host:    h,
		tun:     t,
		router:  r,
		mtu:     mtu,
		ctx:     ctx,
		cancel:  cancel,
		streams: make(map[libp2ppeer.ID]*bufio.Writer),
	}
}

// Start installs the stream handler and launches the TUN reader.
func (d *DataPath) Start() {
	d.host.SetStreamHandler(VPNProtocol, d.handleStream)

	d.wg.Add(1)
	go d.readFromTUN()
}

// Close stops the pumps.
func (d *DataPath) Close() {
	d.cancel()
	d.host.RemoveStreamHandler(VPNProtocol)
	d.wg.Wait()
}

// readFromTUN pulls packets from the interface and forwards them.
func (d *DataPath) readFromTUN() {
	defer d.wg.Done()
	buf := make([]byte, d.mtu+128)
	for {
		n, err := d.tun.ReadPacket(buf)
		if err != nil {
			if d.ctx.Err() != nil {
				return
			}
			log.Printf("tunnel: tun read: %v", err)
			return
		}
		if n <= 0 {
			continue
		}
		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		d.stats.inc(func(s *Stats) { s.FromTUN++ })
		if err := d.send(pkt); err != nil {
			d.stats.inc(func(s *Stats) { s.SendError++ })
			log.Printf("tunnel: send: %v", err)
		}
	}
}

// send routes one IP packet to the peer owning its destination VIP.
func (d *DataPath) send(pkt []byte) error {
	dst, err := ExtractDstVIP(pkt)
	if err != nil {
		return fmt.Errorf("extract dst: %w", err)
	}
	entry, ok := d.router.Lookup(dst)
	if !ok {
		d.stats.inc(func(s *Stats) { s.NoRoute++ })
		return fmt.Errorf("no route for vip %d", dst)
	}
	if entry.PeerID == d.host.ID() {
		return nil // packet destined to ourselves: drop (local stack handled it)
	}
	w, err := d.writerFor(entry.PeerID)
	if err != nil {
		return err
	}
	if err := Encode(w, pkt); err != nil {
		return err
	}
	// bufio.Writer never sends anything until it is flushed or full: a packet
	// written here would sit in memory forever on a quiet link.
	return w.Flush()
}

// writerFor returns (opening if needed) a buffered writer on a stream to p.
func (d *DataPath) writerFor(p libp2ppeer.ID) (*bufio.Writer, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if w, ok := d.streams[p]; ok {
		return w, nil
	}
	ctx, cancel := context.WithTimeout(d.ctx, 20*time.Second)
	defer cancel()
	s, err := d.host.NewStream(ctx, p, VPNProtocol)
	if err != nil {
		return nil, fmt.Errorf("open stream to %s: %w", p, err)
	}
	w := bufio.NewWriterSize(s, d.mtu+128)
	d.streams[p] = w
	return w, nil
}

// handleStream reads packets arriving from a peer and writes them to the TUN.
func (d *DataPath) handleStream(s network.Stream) {
	defer s.Close()
	r := bufio.NewReaderSize(s, d.mtu+128)
	for {
		pkt, err := Decode(r)
		if err != nil {
			if d.ctx.Err() == nil && !errors.Is(err, io.EOF) {
				log.Printf("tunnel: stream %s: %v", s.Conn().RemotePeer(), err)
			}
			return
		}
		if _, err := d.tun.WritePacket(pkt); err != nil {
			log.Printf("tunnel: tun write: %v", err)
			return
		}
		d.stats.inc(func(st *Stats) { st.ToTUN++ })
	}
}
