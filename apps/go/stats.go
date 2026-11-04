package main

import (
	"os"
	"strconv"
	"strings"
)

type processStats struct {
	rssMB      float64
	cpuSeconds float64
}

func readProcessStats() processStats {
	status, statusErr := os.ReadFile("/proc/self/status")
	stat, statErr := os.ReadFile("/proc/self/stat")
	if statusErr != nil || statErr != nil {
		return processStats{}
	}
	return processStats{rssMB: parseRSSMB(string(status)), cpuSeconds: parseCPUSeconds(string(stat))}
}

func parseRSSMB(status string) float64 {
	for line := range strings.Lines(status) {
		if fields := strings.Fields(line); len(fields) >= 2 && fields[0] == "VmRSS:" {
			kb, _ := strconv.ParseFloat(fields[1], 64)
			return kb / 1024
		}
	}
	return 0
}

func parseCPUSeconds(stat string) float64 {
	fields := strings.Fields(stat[strings.LastIndexByte(stat, ')')+1:])
	utime, _ := strconv.ParseFloat(fields[11], 64)
	stime, _ := strconv.ParseFloat(fields[12], 64)
	return (utime + stime) / 100
}
