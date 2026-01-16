package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const trivyVulnOutput = "trivy-vulns.json"
const trivySbomOutput = "sbom.cdx.json"

const (
	trivyContainerCacheDir     = "/tmp/trivy-cache"
	trivyContainerMavenRepoDir = "/root/.m2/repository"
	trivyContainerMavenSetting = "/root/.m2/settings.xml"
)

func runTrivyTask(ctx context.Context, conn net.Conn, payload AssignPayload) {
	outputDir, err := os.MkdirTemp("", fmt.Sprintf("secrux-%s-", sanitizeName(payload.TaskID)))
	if err != nil {
		logf("task %s: failed to create output dir: %v", payload.TaskID, err)
		sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, err, -1)
		return
	}
	chmodBestEffort(outputDir, 0777)
	defer os.RemoveAll(outputDir)

	timeout := resolveTrivyTimeout(payload.TimeoutSec, trivyConfig.TimeoutSec)
	taskCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	trivyTimeout := timeout - time.Minute
	if trivyTimeout < 30*time.Second {
		trivyTimeout = timeout
	}
	trivyTimeout = trivyTimeout.Truncate(time.Second)
	trivyTimeoutArg := trivyTimeout.String()

	var binds []string
	var cleanup func()
	var vulnCmd []string
	var sbomCmd []string
	var sbomContent string
	var scanKind string
	var scanTarget string
	var prepNotes []string
	var fsScanDir string
	globalArgs := []string{"--timeout", trivyTimeoutArg}

	source := payload.Source
	if source == nil {
		sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, fmt.Errorf("source is required for trivy"), -1)
		return
	}

	switch {
	case source.Image != nil && strings.TrimSpace(source.Image.Ref) != "":
		ref := strings.TrimSpace(source.Image.Ref)
		binds = []string{
			fmt.Sprintf("%s:/output", outputDir),
			"/var/run/docker.sock:/var/run/docker.sock",
		}
		vulnCmd = append(globalArgs, "image", "--scanners", "vuln", "--format", "json", "--output", "/output/"+trivyVulnOutput, ref)
		sbomCmd = append(globalArgs, "convert", "--format", "cyclonedx", "--output", "/output/"+trivySbomOutput, "/output/"+trivyVulnOutput)
		scanKind = "image"
		scanTarget = ref
		cleanup = func() {}
	case source.Sbom != nil && (strings.TrimSpace(source.Sbom.URL) != "" || strings.TrimSpace(source.Sbom.UploadID) != ""):
		sbomPath := strings.TrimSpace(source.Sbom.URL)
		downloadCleanup := func() {}
		if sbomPath == "" {
			downloaded, cleanup, err := downloadUpload(taskCtx, payload.ApiBaseURL, strings.TrimSpace(source.Sbom.UploadID))
			if err != nil {
				sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, err, -1)
				return
			}
			sbomPath = downloaded
			downloadCleanup = cleanup
		}
		sbomDir, sbomCleanup, err := prepareSbomDir(taskCtx, payload.TaskID, sbomPath)
		if err != nil {
			downloadCleanup()
			sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, err, -1)
			return
		}
		binds = []string{
			fmt.Sprintf("%s:/output", outputDir),
			fmt.Sprintf("%s:/src:ro", sbomDir),
		}
		vulnCmd = append(globalArgs, "sbom", "--scanners", "vuln", "--format", "json", "--output", "/output/"+trivyVulnOutput, "/src/sbom.json")
		sbomContent = readFileSafe(filepath.Join(sbomDir, "sbom.json"))
		scanKind = "sbom"
		scanTarget = "sbom.json"
		cleanup = func() {
			sbomCleanup()
			downloadCleanup()
		}
	default:
		sourceDir, sourceCleanup, err := prepareSource(taskCtx, payload)
		if err != nil {
			sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, err, -1)
			return
		}
		scanDir, scanCleanup, notes, err := prepareTrivyFilesystemScanDir(taskCtx, payload, sourceDir)
		if err != nil {
			sourceCleanup()
			sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, false, "", "", "", nil, err, -1)
			return
		}
		fsScanDir = scanDir
		binds = []string{
			fmt.Sprintf("%s:/output", outputDir),
			fmt.Sprintf("%s:/src:ro", scanDir),
		}
		vulnCmd = append(globalArgs, "fs", "--scanners", "vuln", "--format", "json", "--output", "/output/"+trivyVulnOutput, "/src")
		sbomCmd = append(globalArgs, "convert", "--format", "cyclonedx", "--output", "/output/"+trivySbomOutput, "/output/"+trivyVulnOutput)
		scanKind = "fs"
		scanTarget = "/src"
		prepNotes = append(prepNotes, notes...)
		cleanup = func() {
			scanCleanup()
			sourceCleanup()
		}
	}
	defer cleanup()

	payload.Env = cloneEnv(payload.Env)
	if trivyConfig.InheritProxyEnv {
		inheritProxyEnv(payload.Env)
	}

	trivyBinds, bindNotes := trivyExtraBinds()
	if len(trivyBinds) > 0 {
		binds = append(binds, trivyBinds...)
	}
	prepNotes = append(prepNotes, bindNotes...)

	var combinedLogs strings.Builder
	combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] trivy scan start kind=%s target=%s\n", scanKind, scanTarget))
	combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] trivy engine image=%s\n", payload.Image))
	combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] trivy timeout=%s\n", trivyTimeoutArg))
	for _, note := range prepNotes {
		if note == "" {
			continue
		}
		combinedLogs.WriteString(note)
		if !strings.HasSuffix(note, "\n") {
			combinedLogs.WriteString("\n")
		}
	}
	combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] vuln output=/output/%s\n", trivyVulnOutput))
	if len(sbomCmd) > 0 {
		combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] sbom output=/output/%s\n", trivySbomOutput))
	}

	run := func(cmd []string, suffix string, allowExit1 bool) (int64, string, error) {
		combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] running (%s): trivy %s\n", suffix, strings.Join(cmd, " ")))
		p := payload
		p.Command = cmd
		exitCode, logs, err := runEngineContainer(taskCtx, conn, p, binds, nil, false, suffix)
		if logs != "" {
			combinedLogs.WriteString(logs)
			if !strings.HasSuffix(logs, "\n") {
				combinedLogs.WriteString("\n")
			}
		}
		if err != nil {
			return exitCode, logs, err
		}
		if exitCode != 0 {
			// Trivy can be configured to return exit code 1 when vulnerabilities are found.
			if !allowExit1 || exitCode != 1 {
				return exitCode, logs, fmt.Errorf("trivy exited with code %d", exitCode)
			}
		}
		return exitCode, logs, nil
	}

	exitCode, vulnLogs, vulnErr := run(vulnCmd, "trivy-vulns", true)
	origExitCode := exitCode
	origErr := vulnErr
	if shouldRetryTrivyOffline(scanKind, vulnErr, vulnLogs, taskCtx) {
		combinedLogs.WriteString("[secrux-executor] trivy timeout detected; retrying with --offline-scan\n")
		offlineCmd := append([]string{"--offline-scan"}, vulnCmd...)
		offlineExit, offlineLogs, offlineErr := run(offlineCmd, "trivy-vulns-offline", true)
		if offlineErr != nil {
			if isOfflineScanUnsupported(offlineLogs) {
				combinedLogs.WriteString("[secrux-executor] trivy offline retry skipped: --offline-scan not supported by this Trivy version\n")
				exitCode = origExitCode
				vulnErr = origErr
			} else {
				combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] trivy offline retry failed: %v\n", offlineErr))
				exitCode = offlineExit
				vulnErr = offlineErr
			}
		} else {
			exitCode = offlineExit
			vulnErr = nil
		}
	}

	vulnOutputPath := filepath.Join(outputDir, trivyVulnOutput)

	var sbomErr error
	if len(sbomCmd) > 0 {
		if _, err := os.Stat(vulnOutputPath); err == nil {
			_, _, sbomErr = run(sbomCmd, "trivy-sbom", false)
		} else {
			sbomErr = fmt.Errorf("sbom conversion skipped because /output/%s was not created", trivyVulnOutput)
		}
		if sbomErr != nil {
			combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] sbom generation warning: %v\n", sbomErr))
			var directSbomCmd []string
			switch scanKind {
			case "fs":
				directSbomCmd = append(globalArgs, "fs", "--format", "cyclonedx", "--output", "/output/"+trivySbomOutput, "/src")
			case "image":
				directSbomCmd = append(globalArgs, "image", "--format", "cyclonedx", "--output", "/output/"+trivySbomOutput, scanTarget)
			}
			if len(directSbomCmd) > 0 {
				combinedLogs.WriteString("[secrux-executor] attempting direct sbom generation\n")
				if _, _, err := run(directSbomCmd, "trivy-sbom-direct", false); err == nil {
					sbomErr = nil
				} else {
					sbomErr = err
				}
			}
		}
	}

	vulnPayloadRaw, vulnReadErr := os.ReadFile(vulnOutputPath)
	vulnPayload := strings.TrimSpace(string(vulnPayloadRaw))
	if sbomContent == "" && len(sbomCmd) > 0 {
		sbomContent = readFileSafe(filepath.Join(outputDir, trivySbomOutput))
	}

	artifacts := map[string]string{}
	if sbomContent != "" {
		artifacts["sbom"] = sbomContent
	}

	runErr := vulnErr
	if runErr == nil && vulnReadErr != nil {
		if errors.Is(vulnReadErr, os.ErrNotExist) {
			runErr = fmt.Errorf("trivy produced no vulnerability output at /output/%s (file not created)", trivyVulnOutput)
		} else {
			runErr = fmt.Errorf("trivy produced vulnerability output at /output/%s but it could not be read: %v", trivyVulnOutput, vulnReadErr)
		}
	}
	if runErr == nil && vulnPayload == "" {
		runErr = fmt.Errorf("trivy produced empty vulnerability output at /output/%s", trivyVulnOutput)
	}

	if scanKind == "fs" && vulnPayload != "" && fsScanDir != "" {
		usageJSON, entryCount, err := buildScaUsageIndex(fsScanDir, vulnPayload)
		if err != nil {
			combinedLogs.WriteString(formatUsageIndexNote(err, 0))
		} else if usageJSON != "" {
			artifacts["usage-index"] = usageJSON
			combinedLogs.WriteString(formatUsageIndexNote(nil, entryCount))
		} else {
			combinedLogs.WriteString(formatUsageIndexNote(nil, 0))
		}
	}

	if runErr == nil && sbomErr != nil {
		combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] sbom generation warning: %v\n", sbomErr))
	}
	if runErr != nil {
		combinedLogs.WriteString(fmt.Sprintf("[secrux-executor] trivy scan failed: %v\n", runErr))
	}
	success := runErr == nil
	sendTaskResult(conn, payload.TaskID, payload.StageID, payload.StageType, success, truncate(combinedLogs.String(), maxLogBytes), vulnPayload, "", artifacts, runErr, exitCode)
}

func resolveTrivyTimeout(taskTimeoutSec int, configTimeoutSec int) time.Duration {
	if configTimeoutSec > 0 {
		return time.Duration(configTimeoutSec) * time.Second
	}
	if taskTimeoutSec > 0 {
		return time.Duration(taskTimeoutSec) * time.Second
	}
	return 20 * time.Minute
}

func shouldRetryTrivyOffline(scanKind string, err error, logs string, ctx context.Context) bool {
	if err == nil || scanKind != "fs" {
		return false
	}
	if ctx != nil && ctx.Err() != nil {
		return false
	}
	return hasTrivyTimeoutHint(logs)
}

func hasTrivyTimeoutHint(logs string) bool {
	if logs == "" {
		return false
	}
	lower := strings.ToLower(logs)
	return strings.Contains(lower, "analyzer timed out") ||
		strings.Contains(lower, "provide a higher timeout value") ||
		strings.Contains(lower, "context deadline exceeded") ||
		strings.Contains(lower, "timed out")
}

func isOfflineScanUnsupported(logs string) bool {
	if logs == "" {
		return false
	}
	lower := strings.ToLower(logs)
	return strings.Contains(lower, "unknown flag: --offline-scan") ||
		strings.Contains(lower, "flag provided but not defined: --offline-scan")
}

func inheritProxyEnv(env map[string]string) {
	if env == nil {
		return
	}
	keys := []string{
		"HTTP_PROXY",
		"HTTPS_PROXY",
		"NO_PROXY",
		"http_proxy",
		"https_proxy",
		"no_proxy",
	}
	for _, key := range keys {
		if _, ok := env[key]; ok {
			continue
		}
		if value := strings.TrimSpace(os.Getenv(key)); value != "" {
			env[key] = value
		}
	}
}

func trivyExtraBinds() ([]string, []string) {
	binds := make([]string, 0, 3)
	notes := make([]string, 0, 3)

	if cacheHost := expandUserPath(strings.TrimSpace(trivyConfig.CacheHostPath)); cacheHost != "" {
		abs, err := filepath.Abs(cacheHost)
		if err == nil {
			cacheHost = abs
		}
		if err := os.MkdirAll(cacheHost, 0755); err != nil {
			notes = append(notes, fmt.Sprintf("[secrux-executor] trivy cache mount skipped (mkdir failed): %v", err))
		} else {
			binds = append(binds, fmt.Sprintf("%s:%s", cacheHost, trivyContainerCacheDir))
			notes = append(notes, fmt.Sprintf("[secrux-executor] trivy cache mounted host=%s container=%s", cacheHost, trivyContainerCacheDir))
		}
	}

	if repoHost := expandUserPath(strings.TrimSpace(trivyConfig.MavenRepositoryPath)); repoHost != "" {
		abs, err := filepath.Abs(repoHost)
		if err == nil {
			repoHost = abs
		}
		info, err := os.Stat(repoHost)
		if err != nil || !info.IsDir() {
			notes = append(notes, fmt.Sprintf("[secrux-executor] trivy maven repo mount skipped (missing): %s", repoHost))
		} else {
			binds = append(binds, fmt.Sprintf("%s:%s:ro", repoHost, trivyContainerMavenRepoDir))
			notes = append(notes, fmt.Sprintf("[secrux-executor] trivy maven repo mounted host=%s container=%s", repoHost, trivyContainerMavenRepoDir))
		}
	}

	if settingsHost := expandUserPath(strings.TrimSpace(trivyConfig.MavenSettingsPath)); settingsHost != "" {
		abs, err := filepath.Abs(settingsHost)
		if err == nil {
			settingsHost = abs
		}
		info, err := os.Stat(settingsHost)
		if err != nil || info.IsDir() {
			notes = append(notes, fmt.Sprintf("[secrux-executor] trivy maven settings mount skipped (missing): %s", settingsHost))
		} else {
			binds = append(binds, fmt.Sprintf("%s:%s:ro", settingsHost, trivyContainerMavenSetting))
			notes = append(notes, fmt.Sprintf("[secrux-executor] trivy maven settings mounted host=%s container=%s", settingsHost, trivyContainerMavenSetting))
		}
	}

	return binds, notes
}
