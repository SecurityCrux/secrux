package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
)

func chmodBestEffort(path string, mode os.FileMode) {
	if err := os.Chmod(path, mode); err != nil {
		log.Printf("chmod %s to %o failed: %v", path, mode, err)
	}
}

func sanitizeName(taskID string) string {
	if taskID == "" {
		return "task"
	}
	var b strings.Builder
	for _, r := range strings.ToLower(taskID) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' {
			b.WriteRune(r)
		} else {
			b.WriteRune('-')
		}
	}
	return b.String()
}

func cloneEnv(src map[string]string) map[string]string {
	if src == nil {
		return map[string]string{}
	}
	dst := make(map[string]string, len(src))
	for k, v := range src {
		dst[k] = v
	}
	return dst
}

func readFileSafe(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return string(data)
}

func waitForSignal(cancel context.CancelFunc) {
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
	cancel()
}

func hostname() string {
	name, err := os.Hostname()
	if err != nil {
		return "unknown"
	}
	return name
}

func logf(format string, args ...any) {
	log.Printf(format, args...)
}
