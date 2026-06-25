package main

import (
	"log"
	"net"
	"os"
	"strings"

	turn "github.com/pion/turn/v2"
)

const (
	defaultBindAddr = "0.0.0.0:3478"
	defaultRealm    = "remote.desk"
	defaultUser     = "rd"
	defaultPassword = "rdpass"
)

func main() {
	bindAddr := envOrDefault("RD_TURN_BIND_ADDR", defaultBindAddr)
	realm := envOrDefault("RD_TURN_REALM", defaultRealm)
	username := envOrDefault("RD_TURN_USERNAME", defaultUser)
	password := envOrDefault("RD_TURN_PASSWORD", defaultPassword)
	relayIP := resolveRelayIP()

	udpListener, err := net.ListenPacket("udp4", bindAddr)
	if err != nil {
		log.Fatalf("failed to listen on UDP %s: %v", bindAddr, err)
	}
	defer udpListener.Close()

	tcpListener, err := net.Listen("tcp4", bindAddr)
	if err != nil {
		log.Fatalf("failed to listen on TCP %s: %v", bindAddr, err)
	}
	defer tcpListener.Close()

	server, err := turn.NewServer(turn.ServerConfig{
		Realm: realm,
		AuthHandler: func(requestUser, requestRealm string, srcAddr net.Addr) ([]byte, bool) {
			if requestUser != username {
				log.Printf(
					"TURN auth rejected user=%s realm=%s remote=%s",
					requestUser,
					requestRealm,
					remoteAddrString(srcAddr),
				)
				return nil, false
			}
			log.Printf(
				"TURN auth accepted user=%s realm=%s remote=%s",
				requestUser,
				requestRealm,
				remoteAddrString(srcAddr),
			)
			return turn.GenerateAuthKey(username, requestRealm, password), true
		},
		PacketConnConfigs: []turn.PacketConnConfig{
			{
				PacketConn: udpListener,
				RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{
					RelayAddress: relayIP,
					Address:      "0.0.0.0",
				},
			},
		},
		ListenerConfigs: []turn.ListenerConfig{
			{
				Listener: tcpListener,
				RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{
					RelayAddress: relayIP,
					Address:      "0.0.0.0",
				},
			},
		},
	})
	if err != nil {
		log.Fatalf("failed to start TURN server: %v", err)
	}
	defer server.Close()

	log.Printf(
		"TURN server started on %s (udp+tcp), relay_ip=%s, realm=%s, username=%s",
		bindAddr,
		relayIP.String(),
		realm,
		username,
	)
	select {}
}

func resolveRelayIP() net.IP {
	if explicit := strings.TrimSpace(os.Getenv("RD_TURN_PUBLIC_IP")); explicit != "" {
		if parsed := net.ParseIP(explicit); parsed != nil && parsed.To4() != nil {
			return parsed.To4()
		}
		log.Printf("invalid RD_TURN_PUBLIC_IP=%q, fallback to auto detect", explicit)
	}

	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
				continue
			}
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, addr := range addrs {
				ip := addressIP(addr)
				if ip == nil {
					continue
				}
				v4 := ip.To4()
				if v4 != nil && v4.IsPrivate() {
					return v4
				}
			}
		}
	}

	return net.ParseIP("127.0.0.1").To4()
}

func addressIP(addr net.Addr) net.IP {
	switch value := addr.(type) {
	case *net.IPNet:
		return value.IP
	case *net.IPAddr:
		return value.IP
	default:
		return nil
	}
}

func envOrDefault(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func remoteAddrString(addr net.Addr) string {
	if addr == nil {
		return "-"
	}
	return addr.String()
}
