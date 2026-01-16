package main

import (
	"runtime"
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/mem"
)

type Metrics struct {
	CPU           float64
	MemoryMB      uint64
	Goroutines    int
	UptimeSeconds int64
}

var startTime = time.Now()

func collectMetrics() Metrics {
	percent, _ := cpu.Percent(0, false)
	vm, _ := mem.VirtualMemory()
	return Metrics{
		CPU:           first(percent),
		MemoryMB:      vm.Used / (1024 * 1024),
		Goroutines:    runtime.NumGoroutine(),
		UptimeSeconds: int64(time.Since(startTime).Seconds()),
	}
}

func first(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	return values[0]
}
