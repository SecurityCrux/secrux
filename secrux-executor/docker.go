package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/image"
	"github.com/docker/docker/client"
	"github.com/docker/docker/errdefs"
	"github.com/docker/docker/pkg/stdcopy"
)

var dockerClient *client.Client

func pullImage(ctx context.Context, imageRef string) error {
	reader, err := dockerClient.ImagePull(ctx, imageRef, image.PullOptions{})
	if err != nil {
		return err
	}
	defer reader.Close()
	_, err = io.Copy(io.Discard, reader)
	return err
}

func ensureImageAvailable(ctx context.Context, imageRef string) error {
	if dockerClient == nil {
		return fmt.Errorf("docker client not initialized")
	}
	if imageRef == "" {
		return fmt.Errorf("image ref is empty")
	}
	_, _, err := dockerClient.ImageInspectWithRaw(ctx, imageRef)
	if err == nil {
		return nil
	}
	if !errdefs.IsNotFound(err) {
		return err
	}
	if strings.HasSuffix(imageRef, ":local") {
		return fmt.Errorf("image %q not found locally (tag=:local). Build it first or update executor config", imageRef)
	}
	return pullImage(ctx, imageRef)
}

func createContainer(ctx context.Context, payload AssignPayload, binds []string, entrypoint []string, name string, autoRemove bool) (string, error) {
	cfg := &container.Config{
		Image: payload.Image,
		Env:   envMapToSlice(payload.Env),
	}
	if strings.EqualFold(payload.Engine, "trivy") {
		cfg.User = "0"
	}
	if len(payload.Command) > 0 {
		cfg.Cmd = payload.Command
	}
	if len(entrypoint) > 0 {
		cfg.Entrypoint = entrypoint
	}

	hostCfg := &container.HostConfig{
		AutoRemove: autoRemove,
		Resources: container.Resources{
			Memory:   memoryBytes(payload.MemoryLimitMb),
			NanoCPUs: nanoCPUs(payload.CpuLimit),
		},
		Binds: binds,
	}

	resp, err := dockerClient.ContainerCreate(ctx, cfg, hostCfg, nil, nil, name)
	if err != nil {
		return "", err
	}
	return resp.ID, nil
}

func runEngineContainer(
	ctx context.Context,
	conn net.Conn,
	payload AssignPayload,
	binds []string,
	entrypoint []string,
	streamLogs bool,
	nameSuffix string,
) (int64, string, error) {
	if err := ensureImageAvailable(ctx, payload.Image); err != nil {
		return -1, "", err
	}

	name := fmt.Sprintf("secrux-%s-%s-%d", sanitizeName(payload.TaskID), sanitizeName(nameSuffix), time.Now().UnixNano())
	autoRemove := streamLogs
	containerID, err := createContainer(ctx, payload, binds, entrypoint, name, autoRemove)
	if err != nil {
		return -1, "", err
	}

	defer func() {
		stopCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = dockerClient.ContainerStop(stopCtx, containerID, container.StopOptions{})
		if !autoRemove {
			_ = dockerClient.ContainerRemove(stopCtx, containerID, container.RemoveOptions{Force: true})
		}
	}()

	if err := dockerClient.ContainerStart(ctx, containerID, container.StartOptions{}); err != nil {
		return -1, "", err
	}

	var (
		logDone    chan struct{}
		cancelLogs context.CancelFunc
	)
	if streamLogs {
		logCtx, cancel := context.WithCancel(context.Background())
		cancelLogs = cancel
		logDone = make(chan struct{})
		go func() {
			defer close(logDone)
			streamContainerLogs(logCtx, conn, payload.TaskID, payload.StageID, payload.StageType, containerID)
		}()
	}

	statusCh, errCh := dockerClient.ContainerWait(ctx, containerID, container.WaitConditionNotRunning)
	var waitErr error
	waitStatus := container.WaitResponse{StatusCode: -1}

	select {
	case err := <-errCh:
		if err != nil {
			waitErr = err
		}
	case status := <-statusCh:
		waitStatus = status
		if status.Error != nil && status.Error.Message != "" {
			waitErr = errors.New(status.Error.Message)
		}
	case <-ctx.Done():
		waitErr = ctx.Err()
	}

	if cancelLogs != nil {
		cancelLogs()
		<-logDone
	}

	var logs string
	if !streamLogs {
		collected, logErr := collectLogs(ctx, containerID)
		if logErr != nil {
			log.Printf("failed to collect logs: %v", logErr)
		} else {
			logs = collected
		}
	}

	return waitStatus.StatusCode, logs, waitErr
}

func envMapToSlice(values map[string]string) []string {
	if len(values) == 0 {
		return nil
	}
	result := make([]string, 0, len(values))
	for k, v := range values {
		result = append(result, fmt.Sprintf("%s=%s", k, v))
	}
	return result
}

func nanoCPUs(limit float64) int64 {
	if limit <= 0 {
		return 0
	}
	return int64(limit * 1_000_000_000)
}

func memoryBytes(megabytes int) int64 {
	if megabytes <= 0 {
		return 0
	}
	return int64(megabytes) * 1024 * 1024
}

func collectLogs(ctx context.Context, containerID string) (string, error) {
	reader, err := dockerClient.ContainerLogs(ctx, containerID, container.LogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Tail:       "all",
	})
	if err != nil {
		return "", err
	}
	defer reader.Close()

	var stdout, stderr bytes.Buffer
	if _, err := stdcopy.StdCopy(&stdout, &stderr, reader); err != nil {
		return "", err
	}
	return stdout.String() + stderr.String(), nil
}

func streamContainerLogs(ctx context.Context, conn net.Conn, taskID string, stageID string, stageType string, containerID string) {
	reader, err := dockerClient.ContainerLogs(ctx, containerID, container.LogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Follow:     true,
		Tail:       "0",
	})
	if err != nil {
		log.Printf("failed to stream logs for task %s: %v", taskID, err)
		return
	}
	defer reader.Close()
	streamer := newLogStreamer(conn, taskID, stageID, stageType)
	stdoutWriter := newLogChunkWriter(streamer, "stdout")
	stderrWriter := newLogChunkWriter(streamer, "stderr")
	defer stdoutWriter.Close()
	defer stderrWriter.Close()
	_, err = stdcopy.StdCopy(stdoutWriter, stderrWriter, reader)
	if err != nil && !errors.Is(err, context.Canceled) {
		log.Printf("log stream error for task %s: %v", taskID, err)
	}
	stdoutWriter.Close()
	stderrWriter.Close()
	streamer.Close()
}
