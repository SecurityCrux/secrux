package main

import (
	"context"
	"encoding/json"
	"log"
	"net"
	"time"
)

func readLoop(ctx context.Context, conn net.Conn, cfg Config) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
			payload, err := readFrame(conn)
			if err != nil {
				log.Printf("read error: %v", err)
				return
			}
			handleMessage(ctx, conn, payload)
		}
	}
}

func heartbeatLoop(ctx context.Context, conn net.Conn, cfg Config) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			metrics := collectMetrics()
			if err := sendMessage(conn, map[string]any{
				"type":           "heartbeat",
				"token":          cfg.Token,
				"cpuUsage":       metrics.CPU,
				"memoryUsageMb":  metrics.MemoryMB,
				"goroutines":     metrics.Goroutines,
				"processUptimeS": metrics.UptimeSeconds,
			}); err != nil {
				log.Printf("heartbeat send error: %v", err)
			}
		}
	}
}

func handleMessage(ctx context.Context, conn net.Conn, msg map[string]any) {
	switch msg["type"] {
	case "register_ack":
		log.Printf("registered with executor ID %v", msg["executorId"])
	case "heartbeat_ack":

	case "task_assign":
		payloadBytes, _ := json.Marshal(msg)
		var assign AssignPayload
		if err := json.Unmarshal(payloadBytes, &assign); err != nil {
			log.Printf("failed to decode task assignment: %v", err)
			return
		}
		go runTask(ctx, conn, assign)
	default:
		log.Printf("unknown message type: %v", msg["type"])
	}
}
