package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"strings"
)

func runTask(ctx context.Context, conn net.Conn, payload AssignPayload) {
	if dockerClient == nil {
		log.Printf("docker client unavailable, cannot run task %s", payload.TaskID)
		sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, fmt.Errorf("docker client not initialized"), -1)
		return
	}

	engine := strings.ToLower(strings.TrimSpace(payload.Engine))
	if engine == "" {
		engine = "semgrep"
	}
	payload.Engine = engine

	image, err := resolveTaskImage(payload)
	if err != nil {
		log.Printf("task %s: %v", payload.TaskID, err)
		sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, err, -1)
		return
	}
	payload.Image = image

	switch engine {
	case "semgrep":
		runSemgrepTask(ctx, conn, payload)
	case "trivy":
		runTrivyTask(ctx, conn, payload)
	default:
		sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, fmt.Errorf("unsupported engine %q", engine), -1)
	}
}
