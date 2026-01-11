package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"log"
	"os"

	"github.com/docker/docker/client"
)

func buildTLSConfig(cfg Config) (*tls.Config, error) {
	tlsConfig := &tls.Config{
		InsecureSkipVerify: cfg.Insecure, // controlled via config/flag
		MinVersion:         tls.VersionTLS12,
	}
	if cfg.ServerName != "" {
		tlsConfig.ServerName = cfg.ServerName
	}
	if cfg.Insecure || cfg.CACertPath == "" {
		return tlsConfig, nil
	}
	pem, err := os.ReadFile(cfg.CACertPath)
	if err != nil {
		return nil, err
	}
	roots, err := x509.SystemCertPool()
	if err != nil || roots == nil {
		roots = x509.NewCertPool()
	}
	if ok := roots.AppendCertsFromPEM(pem); !ok {
		return nil, fmt.Errorf("failed to parse CA certificate %s", cfg.CACertPath)
	}
	tlsConfig.RootCAs = roots
	return tlsConfig, nil
}

func main() {
	cfg := parseConfig()
	if cfg.Token == "" {
		log.Fatal("token is required (obtain from control plane register API)")
	}
	executorAuthToken = cfg.Token

	dc, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		log.Fatalf("failed to initialize docker client: %v", err)
	}
	defer dc.Close()
	dockerClient = dc

	engineImageMap = buildEngineImageMap(cfg.EngineImages)
	log.Printf("loaded engine registry: %v", engineImageMap)
	trivyConfig = cfg.Trivy
	log.Printf(
		"loaded trivy config: sanitizePomRepositories=%t filesystemCopyMode=%s bannedMavenRepoHosts=%v",
		trivyConfig.SanitizePomRepositories,
		trivyConfig.FilesystemCopyMode,
		trivyConfig.BannedMavenRepoHosts,
	)

	tlsConfig, err := buildTLSConfig(cfg)
	if err != nil {
		log.Fatalf("failed to initialize TLS: %v", err)
	}

	conn, err := tls.Dial("tcp", cfg.ServerAddr, tlsConfig)
	if err != nil {
		log.Fatalf("failed to connect to gateway: %v", err)
	}
	defer conn.Close()

	log.Printf("connected to executor gateway %s", cfg.ServerAddr)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go readLoop(ctx, conn, cfg)
	go heartbeatLoop(ctx, conn, cfg)

	if err := sendMessage(conn, map[string]any{
		"type":  "register",
		"token": cfg.Token,
		"host":  hostname(),
	}); err != nil {
		log.Fatalf("register message failed: %v", err)
	}

	waitForSignal(cancel)
}
