package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"time"
)

const semgrepSarifOutput = "semgrep.sarif.json"
const semgrepEngineLog = "semgrep-log.json"

func runSemgrepTask(ctx context.Context, conn net.Conn, payload AssignPayload) {
	outputDir, err := os.MkdirTemp("", fmt.Sprintf("secrux-%s-", sanitizeName(payload.TaskID)))
	if err != nil {
		log.Printf("task %s: failed to create output dir: %v", payload.TaskID, err)
		sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, err, -1)
		return
	}
	chmodBestEffort(outputDir, 0777)
	defer os.RemoveAll(outputDir)

	sourceDir, cleanup, err := prepareSource(ctx, payload)
	if err != nil {
		log.Printf("task %s: failed to prepare source: %v", payload.TaskID, err)
		sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, err, -1)
		return
	}
	defer cleanup()

	env := cloneEnv(payload.Env)
	if _, ok := env["SEMGREP_CONFIG"]; !ok {
		env["SEMGREP_CONFIG"] = "auto"
	}
	env["SEMGREP_ENABLE_SARIF"] = "true"
	env["SEMGREP_OUTPUT_FILE"] = "/output/" + semgrepSarifOutput
	env["SEMGREP_LOG_FILE"] = "/output/" + semgrepEngineLog
	if payload.UsePro && payload.SemgrepToken != "" {
		env["SEMGREP_APP_TOKEN"] = payload.SemgrepToken
		env["SEMGREP_USE_PRO"] = "true"
	}
	payload.Env = env

	if len(payload.Command) == 0 {
		cmd := []string{"scan", "--disable-version-check"}
		if payload.UsePro {
			cmd = append(cmd, "--dataflow-traces")
		}
		cmd = append(cmd, "/src")
		payload.Command = cmd
	}

	timeout := time.Duration(payload.TimeoutSec) * time.Second
	if timeout == 0 {
		timeout = 15 * time.Minute
	}
	taskCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	binds := []string{
		fmt.Sprintf("%s:/output", outputDir),
		fmt.Sprintf("%s:/src:ro", sourceDir),
	}

	exitCode, logs, waitErr := runEngineContainer(taskCtx, conn, payload, binds, nil, true, "semgrep")
	resultPayload := readFileSafe(filepath.Join(outputDir, semgrepSarifOutput))
	runLogPayload := readFileSafe(filepath.Join(outputDir, semgrepEngineLog))

	success := waitErr == nil && (exitCode == 0 || exitCode == 1)
	if success && resultPayload == "" {
		success = false
		waitErr = fmt.Errorf("semgrep produced no SARIF output")
	}
	sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, success, truncate(logs, maxLogBytes), resultPayload, runLogPayload, nil, waitErr, exitCode)
}
