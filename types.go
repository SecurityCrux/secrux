package main

type Config struct {
	ServerAddr   string
	ServerName   string
	CACertPath   string
	Token        string
	Insecure     bool
	EngineImages map[string]string
	Trivy        TrivyConfig
}

type fileConfig struct {
	Server       string            `json:"server"`
	ServerName   string            `json:"serverName"`
	CACertPath   string            `json:"caCertPath"`
	Token        string            `json:"token"`
	Insecure     *bool             `json:"insecure"`
	EngineImages map[string]string `json:"engineImages"`
	Trivy        *TrivyFileConfig  `json:"trivy,omitempty"`
}

type TrivyConfig struct {
	SanitizePomRepositories bool
	BannedMavenRepoHosts    []string
	FilesystemCopyMode      string
	MavenRepositoryPath     string
	MavenSettingsPath       string
	CacheHostPath           string
	InheritProxyEnv         bool
}

type TrivyFileConfig struct {
	SanitizePomRepositories *bool    `json:"sanitizePomRepositories"`
	BannedMavenRepoHosts    []string `json:"bannedMavenRepoHosts"`
	FilesystemCopyMode      string   `json:"filesystemCopyMode"`
	MavenRepositoryPath     string   `json:"mavenRepositoryPath"`
	MavenSettingsPath       string   `json:"mavenSettingsPath"`
	CacheHostPath           string   `json:"cacheHostPath"`
	InheritProxyEnv         *bool    `json:"inheritProxyEnv"`
}

type AssignPayload struct {
	TaskID        string            `json:"taskId"`
	StageID       string            `json:"stageId"`
	StageType     string            `json:"stageType"`
	Engine        string            `json:"engine"`
	Image         string            `json:"image"`
	Command       []string          `json:"command"`
	Env           map[string]string `json:"env"`
	CpuLimit      float64           `json:"cpuLimit"`
	MemoryLimitMb int               `json:"memoryLimitMb"`
	TimeoutSec    int               `json:"timeoutSec"`
	UsePro        bool              `json:"usePro"`
	SemgrepToken  string            `json:"semgrepToken"`
	ApiBaseURL    string            `json:"apiBaseUrl"`
	Source        *SourceSpec       `json:"source"`
	OutputPrefix  string            `json:"outputPrefix"`
}

type SourceSpec struct {
	Git        *GitSourceSpec        `json:"git,omitempty"`
	Archive    *ArchiveSourceSpec    `json:"archive,omitempty"`
	Filesystem *FilesystemSourceSpec `json:"filesystem,omitempty"`
	Image      *ImageSourceSpec      `json:"image,omitempty"`
	Sbom       *SbomSourceSpec       `json:"sbom,omitempty"`
	URL        *URLSourceSpec        `json:"url,omitempty"`
}

type GitSourceSpec struct {
	Repo    string            `json:"repo"`
	Ref     string            `json:"ref"`
	RefType string            `json:"refType"`
	Auth    map[string]string `json:"auth"`
}

type ArchiveSourceSpec struct {
	URL      string `json:"url"`
	UploadID string `json:"uploadId"`
}

type FilesystemSourceSpec struct {
	Path     string `json:"path"`
	UploadID string `json:"uploadId"`
}

type ImageSourceSpec struct {
	Ref string `json:"ref"`
}

type SbomSourceSpec struct {
	UploadID string `json:"uploadId"`
	URL      string `json:"url"`
}

type URLSourceSpec struct {
	URL string `json:"url"`
}
