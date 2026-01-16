package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
)

const maxLogBytes = 16_384
const logChunkSize = 2048
const maxFrameBytes uint32 = 5 * 1024 * 1024

var sendMu sync.Mutex

func sendTaskResult(
	conn net.Conn,
	taskID string,
	stageID string,
	stageType string,
	success bool,
	logs string,
	resultJSON string,
	runLogJSON string,
	artifacts map[string]string,
	taskErr error,
	exitCode int64,
) {
	result := map[string]any{
		"type":      "task_result",
		"taskId":    taskID,
		"stageId":   stageID,
		"stageType": stageType,
		"success":   success,
		"log":       logs,
		"result":    resultJSON,
		"runLog":    runLogJSON,
		"exitCode":  exitCode,
	}
	if len(artifacts) > 0 {
		result["artifacts"] = artifacts
	}
	if taskErr != nil {
		result["error"] = taskErr.Error()
	}
	if err := sendMessage(conn, result); err != nil {
		log.Printf("failed to send task result: %v", err)
	}
}

func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max]
}

func sendMessage(conn net.Conn, payload map[string]any) error {
	data, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	frame := new(bytes.Buffer)
	if err := binary.Write(frame, binary.BigEndian, uint32(len(data))); err != nil {
		return err
	}
	if _, err := frame.Write(data); err != nil {
		return err
	}
	sendMu.Lock()
	defer sendMu.Unlock()
	_, err = conn.Write(frame.Bytes())
	return err
}

func readFrame(conn net.Conn) (map[string]any, error) {
	var length uint32
	if err := binary.Read(conn, binary.BigEndian, &length); err != nil {
		return nil, err
	}
	if length == 0 {
		return nil, fmt.Errorf("invalid frame length 0")
	}
	if length > maxFrameBytes {
		return nil, fmt.Errorf("frame too large: %d bytes (max=%d)", length, maxFrameBytes)
	}
	data := make([]byte, length)
	if _, err := io.ReadFull(conn, data); err != nil {
		return nil, err
	}
	var payload map[string]any
	if err := json.Unmarshal(data, &payload); err != nil {
		return nil, err
	}
	return payload, nil
}

type logStreamer struct {
	conn      net.Conn
	taskID    string
	stageID   string
	stageType string
	seq       int64
	mu        sync.Mutex
}

func newLogStreamer(conn net.Conn, taskID string, stageID string, stageType string) *logStreamer {
	return &logStreamer{
		conn:      conn,
		taskID:    taskID,
		stageID:   stageID,
		stageType: stageType,
	}
}

func (ls *logStreamer) send(stream string, content string, last bool) {
	if !last && len(content) == 0 {
		return
	}
	ls.mu.Lock()
	sequence := ls.seq
	ls.seq++
	ls.mu.Unlock()
	payload := map[string]any{
		"type":     "log_chunk",
		"taskId":   ls.taskID,
		"sequence": sequence,
		"stream":   stream,
		"content":  content,
		"isLast":   last,
	}
	if ls.stageID != "" {
		payload["stageId"] = ls.stageID
	}
	if ls.stageType != "" {
		payload["stageType"] = ls.stageType
	}
	if err := sendMessage(ls.conn, payload); err != nil {
		log.Printf("failed to send log chunk: %v", err)
	}
}

func (ls *logStreamer) Close() {}

type logChunkWriter struct {
	streamer *logStreamer
	stream   string
	buf      bytes.Buffer
	closed   bool
}

func newLogChunkWriter(streamer *logStreamer, stream string) *logChunkWriter {
	return &logChunkWriter{
		streamer: streamer,
		stream:   stream,
	}
}

func (w *logChunkWriter) Write(p []byte) (int, error) {
	if w.closed {
		return 0, io.EOF
	}
	if len(p) == 0 {
		return 0, nil
	}
	if _, err := w.buf.Write(p); err != nil {
		return 0, err
	}
	for w.buf.Len() >= logChunkSize {
		chunk := w.buf.Next(logChunkSize)
		w.streamer.send(w.stream, string(chunk), false)
	}
	return len(p), nil
}

func (w *logChunkWriter) Close() {
	if w.closed {
		return
	}
	if w.buf.Len() > 0 {
		w.streamer.send(w.stream, w.buf.String(), false)
		w.buf.Reset()
	}
	w.streamer.send(w.stream, "", true)
	w.closed = true
}
