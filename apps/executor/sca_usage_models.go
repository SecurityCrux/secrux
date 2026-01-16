package main

type scaUsageIndex struct {
	GeneratedAt  string               `json:"generatedAt,omitempty"`
	ScannedFiles int                  `json:"scannedFiles,omitempty"`
	Entries      []scaUsageIndexEntry `json:"entries"`
}

type scaUsageIndexEntry struct {
	Ecosystem string `json:"ecosystem,omitempty"`
	Key       string `json:"key,omitempty"`
	File      string `json:"file,omitempty"`
	Line      int    `json:"line,omitempty"`
	Kind      string `json:"kind,omitempty"`
	Snippet   string `json:"snippet,omitempty"`

	Language   string  `json:"language,omitempty"`
	Symbol     string  `json:"symbol,omitempty"`
	Receiver   string  `json:"receiver,omitempty"`
	Callee     string  `json:"callee,omitempty"`
	StartLine  int     `json:"startLine,omitempty"`
	StartCol   int     `json:"startCol,omitempty"`
	EndLine    int     `json:"endLine,omitempty"`
	EndCol     int     `json:"endCol,omitempty"`
	Confidence float64 `json:"confidence,omitempty"`
}

type scaUsagePackage struct {
	ecosystem string
	key       string
	tokens    []string
}

type mavenUsagePackage struct {
	key       string
	groupID   string
	artifact  string
	ecosystem string
}
