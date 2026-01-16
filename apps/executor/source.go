package main

import (
	"archive/zip"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

func prepareSbomDir(ctx context.Context, taskID string, sbomPath string) (string, func(), error) {
	if strings.TrimSpace(sbomPath) == "" {
		return "", func() {}, fmt.Errorf("sbom path is blank")
	}
	info, err := os.Stat(sbomPath)
	if err != nil {
		return "", func() {}, fmt.Errorf("sbom path not accessible: %w", err)
	}
	if info.IsDir() {
		return "", func() {}, fmt.Errorf("sbom path is a directory: %s", sbomPath)
	}
	dir, err := os.MkdirTemp("", fmt.Sprintf("secrux-sbom-%s-", sanitizeName(taskID)))
	if err != nil {
		return "", func() {}, err
	}
	chmodBestEffort(dir, 0755)
	cleanup := func() { _ = os.RemoveAll(dir) }
	target := filepath.Join(dir, "sbom.json")
	if err := copyFile(sbomPath, target); err != nil {
		cleanup()
		return "", func() {}, err
	}
	return dir, cleanup, nil
}

func prepareSource(ctx context.Context, payload AssignPayload) (string, func(), error) {
	source := payload.Source
	if source == nil {
		return "", func() {}, fmt.Errorf("source is required")
	}
	if source.Filesystem != nil && strings.TrimSpace(source.Filesystem.Path) == "" && strings.TrimSpace(source.Filesystem.UploadID) != "" {
		uploadID := strings.TrimSpace(source.Filesystem.UploadID)
		downloaded, downloadCleanup, err := downloadUpload(ctx, payload.ApiBaseURL, uploadID)
		if err != nil {
			return "", func() {}, err
		}
		dest, err := os.MkdirTemp("", fmt.Sprintf("secrux-fs-upload-%s-", sanitizeName(payload.TaskID)))
		if err != nil {
			downloadCleanup()
			return "", func() {}, err
		}
		chmodBestEffort(dest, 0755)
		cleanup := func() {
			downloadCleanup()
			_ = os.RemoveAll(dest)
		}
		if err := unzipToDir(downloaded, dest); err != nil {
			cleanup()
			return "", func() {}, fmt.Errorf("failed to extract filesystem upload %s: %w", uploadID, err)
		}
		return dest, cleanup, nil
	}
	if source.Filesystem != nil && strings.TrimSpace(source.Filesystem.Path) != "" {
		path := strings.TrimSpace(source.Filesystem.Path)
		abs, err := filepath.Abs(path)
		if err == nil {
			path = abs
		}
		info, err := os.Stat(path)
		if err != nil {
			return "", func() {}, fmt.Errorf("filesystem path not accessible: %w", err)
		}
		if !info.IsDir() {
			return "", func() {}, fmt.Errorf("filesystem path is not a directory: %s", path)
		}
		return path, func() {}, nil
	}
	if source.Git != nil && strings.TrimSpace(source.Git.Repo) != "" {
		return prepareGitRepo(ctx, payload.TaskID, source.Git)
	}
	if source.Archive != nil {
		return prepareArchive(ctx, payload.TaskID, payload.ApiBaseURL, source.Archive)
	}
	return "", func() {}, fmt.Errorf("unsupported source type")
}

func prepareGitRepo(ctx context.Context, taskID string, spec *GitSourceSpec) (string, func(), error) {
	repo := strings.TrimSpace(spec.Repo)
	if repo == "" {
		return "", func() {}, fmt.Errorf("git repo is blank")
	}
	if !isRemoteRepo(repo) {
		path := repo
		abs, err := filepath.Abs(path)
		if err == nil {
			path = abs
		}
		info, err := os.Stat(path)
		if err != nil {
			return "", func() {}, fmt.Errorf("git path not accessible: %w", err)
		}
		if !info.IsDir() {
			return "", func() {}, fmt.Errorf("git path is not a directory: %s", path)
		}
		return path, func() {}, nil
	}

	workDir, err := os.MkdirTemp("", fmt.Sprintf("secrux-src-%s-", sanitizeName(taskID)))
	if err != nil {
		return "", func() {}, err
	}
	chmodBestEffort(workDir, 0755)
	cleanup := func() { _ = os.RemoveAll(workDir) }

	cloneURL := applyGitAuth(repo, spec.Auth)
	ref := strings.TrimSpace(spec.Ref)
	refType := strings.ToUpper(strings.TrimSpace(spec.RefType))
	if refType == "" {
		refType = "BRANCH"
	}

	args := []string{"clone"}
	if ref != "" && (refType == "BRANCH" || refType == "TAG") {
		args = append(args, "--depth", "1", "--branch", ref)
	}
	args = append(args, cloneURL, workDir)
	if err := runCommand(ctx, "", "git", args, map[string]string{"GIT_TERMINAL_PROMPT": "0"}); err != nil {
		cleanup()
		return "", func() {}, err
	}
	if ref != "" && refType == "COMMIT" {
		if err := runCommand(ctx, workDir, "git", []string{"checkout", ref}, map[string]string{"GIT_TERMINAL_PROMPT": "0"}); err != nil {
			cleanup()
			return "", func() {}, err
		}
	}
	return workDir, cleanup, nil
}

func prepareArchive(ctx context.Context, taskID string, apiBaseURL string, spec *ArchiveSourceSpec) (string, func(), error) {
	if spec == nil {
		return "", func() {}, fmt.Errorf("archive source missing")
	}
	archivePath := strings.TrimSpace(spec.URL)
	archiveCleanup := func() {}
	fromUpload := false
	if uploadID := strings.TrimSpace(spec.UploadID); uploadID != "" {
		downloaded, cleanup, err := downloadUpload(ctx, apiBaseURL, uploadID)
		if err != nil {
			return "", func() {}, err
		}
		archivePath = downloaded
		archiveCleanup = cleanup
		fromUpload = true
	} else if archivePath == "" {
		return "", func() {}, fmt.Errorf("archive.uploadId or archive.url is required")
	}
	info, err := os.Stat(archivePath)
	if err != nil {
		archiveCleanup()
		return "", func() {}, fmt.Errorf("archive path not accessible: %w", err)
	}
	if info.IsDir() {
		archiveCleanup()
		return "", func() {}, fmt.Errorf("archive path is a directory: %s", archivePath)
	}
	if !fromUpload && strings.ToLower(filepath.Ext(archivePath)) != ".zip" {
		archiveCleanup()
		return "", func() {}, fmt.Errorf("unsupported archive format %s (only .zip)", filepath.Ext(archivePath))
	}
	dest, err := os.MkdirTemp("", fmt.Sprintf("secrux-archive-%s-", sanitizeName(taskID)))
	if err != nil {
		archiveCleanup()
		return "", func() {}, err
	}
	chmodBestEffort(dest, 0755)
	cleanup := func() {
		archiveCleanup()
		_ = os.RemoveAll(dest)
	}
	if err := unzipToDir(archivePath, dest); err != nil {
		cleanup()
		return "", func() {}, err
	}
	return dest, cleanup, nil
}

func isRemoteRepo(repo string) bool {
	value := strings.TrimSpace(repo)
	if value == "" {
		return false
	}
	if strings.HasPrefix(value, "git@") {
		return true
	}
	return strings.Contains(value, "://")
}

func applyGitAuth(raw string, auth map[string]string) string {
	if len(auth) == 0 {
		return raw
	}
	u, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return raw
	}
	var username string
	var password string
	if token := strings.TrimSpace(auth["token"]); token != "" {
		username = strings.TrimSpace(auth["username"])
		if username == "" {
			username = "token"
		}
		password = token
	} else if user := strings.TrimSpace(auth["username"]); user != "" {
		if pass := strings.TrimSpace(auth["password"]); pass != "" {
			username = user
			password = pass
		}
	}
	if username == "" || password == "" {
		return raw
	}
	u.User = url.UserPassword(username, password)
	return u.String()
}

func downloadUpload(ctx context.Context, apiBaseURL string, uploadID string) (string, func(), error) {
	base := strings.TrimRight(strings.TrimSpace(apiBaseURL), "/")
	if base == "" {
		return "", func() {}, fmt.Errorf("apiBaseUrl is required to download uploads")
	}
	id := strings.TrimSpace(uploadID)
	if id == "" {
		return "", func() {}, fmt.Errorf("uploadId is blank")
	}
	if executorAuthToken == "" {
		return "", func() {}, fmt.Errorf("executor token is missing")
	}

	requestURL := base + "/executor/uploads/" + url.PathEscape(id)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, requestURL, nil)
	if err != nil {
		return "", func() {}, err
	}
	req.Header.Set("X-Executor-Token", executorAuthToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", func() {}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return "", func() {}, fmt.Errorf("upload download failed: status=%d body=%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}

	tmp, err := os.CreateTemp("", fmt.Sprintf("secrux-upload-%s-", sanitizeName(id)))
	if err != nil {
		return "", func() {}, err
	}
	defer tmp.Close()
	if _, err := io.Copy(tmp, resp.Body); err != nil {
		_ = os.Remove(tmp.Name())
		return "", func() {}, err
	}
	path := tmp.Name()
	cleanup := func() { _ = os.Remove(path) }
	return path, cleanup, nil
}

func runCommand(ctx context.Context, dir string, name string, args []string, extraEnv map[string]string) error {
	cmd := exec.CommandContext(ctx, name, args...)
	if dir != "" {
		cmd.Dir = dir
	}
	cmd.Env = os.Environ()
	for k, v := range extraEnv {
		cmd.Env = append(cmd.Env, fmt.Sprintf("%s=%s", k, v))
	}
	out, err := cmd.CombinedOutput()
	if err != nil {
		safeArgs := args
		if name == "git" {
			safeArgs = redactGitArgs(args)
		}
		return fmt.Errorf("%s %s failed: %w (%s)", name, strings.Join(safeArgs, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}

func redactGitArgs(args []string) []string {
	redacted := make([]string, 0, len(args))
	for _, arg := range args {
		u, err := url.Parse(arg)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.User == nil {
			redacted = append(redacted, arg)
			continue
		}
		username := u.User.Username()
		u.User = url.User(username)
		redacted = append(redacted, u.String())
	}
	return redacted
}

func copyFile(src string, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0755); err != nil {
		return err
	}
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	return out.Close()
}

func unzipToDir(zipPath string, destDir string) error {
	reader, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer reader.Close()

	destAbs, err := filepath.Abs(destDir)
	if err != nil {
		return err
	}
	destAbs = filepath.Clean(destAbs) + string(os.PathSeparator)

	for _, f := range reader.File {
		name := filepath.Clean(f.Name)
		if name == "." || name == string(os.PathSeparator) {
			continue
		}
		if strings.HasPrefix(name, ".."+string(os.PathSeparator)) || strings.Contains(name, ":") {
			return fmt.Errorf("invalid zip entry %q", f.Name)
		}
		target := filepath.Join(destDir, name)
		targetAbs, err := filepath.Abs(target)
		if err != nil {
			return err
		}
		targetAbs = filepath.Clean(targetAbs)
		if !strings.HasPrefix(targetAbs+string(os.PathSeparator), destAbs) && targetAbs != strings.TrimSuffix(destAbs, string(os.PathSeparator)) {
			return fmt.Errorf("zip entry escapes destination: %q", f.Name)
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(targetAbs, 0755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(targetAbs), 0755); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(targetAbs, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, f.Mode())
		if err != nil {
			rc.Close()
			return err
		}
		if _, err := io.Copy(out, rc); err != nil {
			out.Close()
			rc.Close()
			return err
		}
		out.Close()
		rc.Close()
	}
	return nil
}
