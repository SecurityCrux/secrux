package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
)

func parseConfig() Config {
	cfg := Config{
		ServerAddr:   "gateway.secrux.internal:5155",
		Insecure:     false,
		EngineImages: map[string]string{},
		Trivy:        defaultTrivyConfig(),
	}

	var configPath string
	flag.StringVar(&configPath, "config", "", "path to executor config file (JSON)")
	flag.StringVar(&cfg.ServerAddr, "server", cfg.ServerAddr, "executor gateway address")
	flag.StringVar(&cfg.ServerName, "server-name", cfg.ServerName, "TLS server name override (for certificate verification)")
	flag.StringVar(&cfg.CACertPath, "ca-cert", cfg.CACertPath, "path to gateway CA certificate (PEM)")
	flag.StringVar(&cfg.Token, "token", os.Getenv("EXECUTOR_TOKEN"), "provisioned executor token")
	flag.BoolVar(&cfg.Insecure, "insecure", cfg.Insecure, "skip TLS verification (dev only)")
	flag.Parse()

	overrides := map[string]bool{}
	flag.Visit(func(f *flag.Flag) {
		overrides[f.Name] = true
	})

	if configPath != "" {
		fileCfg, err := loadConfigFile(configPath)
		if err != nil {
			log.Fatalf("failed to load config file: %v", err)
		}
		if !overrides["server"] && fileCfg.Server != "" {
			cfg.ServerAddr = fileCfg.Server
		}
		if !overrides["server-name"] && strings.TrimSpace(fileCfg.ServerName) != "" {
			cfg.ServerName = strings.TrimSpace(fileCfg.ServerName)
		}
		if !overrides["ca-cert"] && strings.TrimSpace(fileCfg.CACertPath) != "" {
			cfg.CACertPath = expandUserPath(strings.TrimSpace(fileCfg.CACertPath))
		}
		if !overrides["token"] && fileCfg.Token != "" {
			cfg.Token = fileCfg.Token
		}
		if !overrides["insecure"] && fileCfg.Insecure != nil {
			cfg.Insecure = *fileCfg.Insecure
		}
		if len(fileCfg.EngineImages) > 0 {
			cfg.EngineImages = mergeEngineMaps(cfg.EngineImages, fileCfg.EngineImages)
		}
		if fileCfg.Trivy != nil {
			if fileCfg.Trivy.SanitizePomRepositories != nil {
				cfg.Trivy.SanitizePomRepositories = *fileCfg.Trivy.SanitizePomRepositories
			}
			if len(fileCfg.Trivy.BannedMavenRepoHosts) > 0 {
				cfg.Trivy.BannedMavenRepoHosts = append([]string{}, fileCfg.Trivy.BannedMavenRepoHosts...)
			}
			if strings.TrimSpace(fileCfg.Trivy.FilesystemCopyMode) != "" {
				cfg.Trivy.FilesystemCopyMode = strings.TrimSpace(fileCfg.Trivy.FilesystemCopyMode)
			}
			if strings.TrimSpace(fileCfg.Trivy.MavenRepositoryPath) != "" {
				cfg.Trivy.MavenRepositoryPath = expandUserPath(strings.TrimSpace(fileCfg.Trivy.MavenRepositoryPath))
			}
			if strings.TrimSpace(fileCfg.Trivy.MavenSettingsPath) != "" {
				cfg.Trivy.MavenSettingsPath = expandUserPath(strings.TrimSpace(fileCfg.Trivy.MavenSettingsPath))
			}
			if strings.TrimSpace(fileCfg.Trivy.CacheHostPath) != "" {
				cfg.Trivy.CacheHostPath = expandUserPath(strings.TrimSpace(fileCfg.Trivy.CacheHostPath))
			}
			if fileCfg.Trivy.InheritProxyEnv != nil {
				cfg.Trivy.InheritProxyEnv = *fileCfg.Trivy.InheritProxyEnv
			}
		}
	}

	cfg.ServerName = strings.TrimSpace(cfg.ServerName)
	cfg.CACertPath = expandUserPath(cfg.CACertPath)

	return cfg
}

func expandUserPath(path string) string {
	value := strings.TrimSpace(path)
	if value == "" {
		return value
	}
	if value == "~" {
		home, err := os.UserHomeDir()
		if err != nil {
			return value
		}
		return home
	}
	if strings.HasPrefix(value, "~/") || strings.HasPrefix(value, "~\\") {
		home, err := os.UserHomeDir()
		if err != nil {
			return value
		}
		return filepath.Join(home, value[2:])
	}
	return value
}

func defaultTrivyConfig() TrivyConfig {
	home, _ := os.UserHomeDir()
	mavenRepo := ""
	mavenSettings := ""
	cacheHostPath := ""
	if strings.TrimSpace(home) != "" {
		mavenRepo = filepath.Join(home, ".m2", "repository")
		mavenSettings = filepath.Join(home, ".m2", "settings.xml")
		cacheHostPath = filepath.Join(home, ".cache", "secrux", "trivy")
	}
	return TrivyConfig{
		SanitizePomRepositories: true,
		BannedMavenRepoHosts: []string{
			"dl.bintray.com",
			"jcenter.bintray.com",
			"repo.bintray.com",
		},
		FilesystemCopyMode:  "auto",
		MavenRepositoryPath: mavenRepo,
		MavenSettingsPath:   mavenSettings,
		CacheHostPath:       cacheHostPath,
		InheritProxyEnv:     true,
	}
}

func loadConfigFile(path string) (fileConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return fileConfig{}, err
	}
	ext := strings.ToLower(filepath.Ext(path))
	if ext != "" && ext != ".json" {
		return fileConfig{}, fmt.Errorf("unsupported config format %s (only JSON)", ext)
	}
	var cfg fileConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return fileConfig{}, err
	}
	return cfg, nil
}

func resolveTaskImage(payload AssignPayload) (string, error) {
	if payload.Image != "" {
		return payload.Image, nil
	}
	if payload.Engine == "" {
		return "", fmt.Errorf("engine not provided and no image override")
	}
	if engineImageMap == nil {
		return "", fmt.Errorf("engine registry not initialized")
	}
	if image, ok := engineImageMap[strings.ToLower(payload.Engine)]; ok && image != "" {
		return image, nil
	}
	return "", fmt.Errorf("engine %s is not configured on this executor", payload.Engine)
}

func parseEngineImageMap(raw string) map[string]string {
	result := map[string]string{}
	if raw == "" {
		return result
	}
	entries := strings.Split(raw, ",")
	for _, entry := range entries {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		parts := strings.SplitN(entry, "=", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(parts[0]))
		value := strings.TrimSpace(parts[1])
		if key == "" || value == "" {
			continue
		}
		result[key] = value
	}
	return result
}

func buildEngineImageMap(base map[string]string) map[string]string {
	registry := map[string]string{}
	registry = mergeEngineMaps(registry, base)
	registry = mergeEngineMaps(registry, parseEngineImageMap(os.Getenv("ENGINE_IMAGE_MAP")))
	if img := os.Getenv("ENGINE_SEMGREP_IMAGE"); img != "" {
		registry["semgrep"] = img
	}
	if img := os.Getenv("ENGINE_TRIVY_IMAGE"); img != "" {
		registry["trivy"] = img
	}
	if len(registry) == 0 {
		registry["semgrep"] = "secrux-semgrep-engine:latest"
	}
	if _, ok := registry["semgrep"]; !ok {
		registry["semgrep"] = "secrux-semgrep-engine:latest"
	}
	if _, ok := registry["trivy"]; !ok {
		registry["trivy"] = "aquasec/trivy:latest"
	}
	return registry
}

func mergeEngineMaps(dst, src map[string]string) map[string]string {
	if dst == nil {
		dst = map[string]string{}
	}
	for key, value := range src {
		nk := strings.ToLower(strings.TrimSpace(key))
		if nk == "" {
			continue
		}
		val := strings.TrimSpace(value)
		if val == "" {
			continue
		}
		dst[nk] = val
	}
	return dst
}
