export type ApiResponse<T> = {
  success: boolean
  code: string
  message?: string | null
  data: T
}

export type PageResponse<T> = {
  items: T[]
  total: number
  limit: number
  offset: number
}

export type OverviewExecutorSummary = {
  total: number
  byStatus: Record<string, number>
}

export type OverviewTaskSummary = {
  running: number
  failed24h: number
  totalWindow: number
  successRateWindow: number
  byStatus: Record<string, number>
}

export type OverviewFindingTrendPoint = {
  date: string
  newCount: number
  closedCount: number
}

export type OverviewFindingSummary = {
  open: number
  confirmed: number
  bySeverityUnresolved: Record<string, number>
  trendWindow: OverviewFindingTrendPoint[]
}

export type OverviewAiSummary = {
  queued: number
  completedWindow: number
  autoAppliedWindow: number
}

export type OverviewSummaryResponse = {
  projects: number
  repositories: number
  executors: OverviewExecutorSummary
  tasks: OverviewTaskSummary
  findings: OverviewFindingSummary
  ai: OverviewAiSummary
}

export type OverviewTaskItem = {
  taskId: string
  projectId: string
  repoId?: string | null
  status: string
  type: string
  name?: string | null
  correlationId: string
  createdAt: string
  updatedAt?: string | null
}

export type OverviewFindingItem = {
  findingId: string
  taskId: string
  projectId: string
  ruleId?: string | null
  severity: string
  status: string
  introducedBy?: string | null
  createdAt: string
}

export type AiJobType = "STAGE_REVIEW" | "FINDING_REVIEW" | "SCA_ISSUE_REVIEW"
export type AiJobStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED"

export type AiJobTicket = {
  jobId: string
  status: AiJobStatus
  jobType: AiJobType
  createdAt: string
  updatedAt: string
  result?: Record<string, unknown> | null
  error?: string | null
}

export type FindingReviewSummary = {
  reviewType: "AI" | "HUMAN" | string
  reviewer: string
  verdict: string
  confidence?: number | null
  statusBefore?: string | null
  statusAfter?: string | null
  opinionI18n?: FindingReviewOpinionI18n | null
  createdAt: string
  appliedAt?: string | null
}

export type FindingReviewOpinionText = {
  summary?: string | null
  fixHint?: string | null
  rationale?: string | null
}

export type FindingReviewOpinionI18n = {
  zh?: FindingReviewOpinionText | null
  en?: FindingReviewOpinionText | null
}

export type RepositorySourceMode = "REMOTE" | "UPLOAD" | "MIXED"
export type RepositoryScmType = "github" | "gitlab" | "gerrit" | "bitbucket" | "git"
export type TaskType = "CODE_CHECK" | "SECURITY_SCAN" | "SUPPLY_CHAIN" | "SCA_CHECK"
export type Severity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO"
export type SourceRefType = "BRANCH" | "TAG" | "COMMIT" | "UPLOAD"

export type Project = {
  projectId: string
  tenantId: string
  name: string
  codeOwners: string[]
  createdAt: string
  updatedAt?: string | null
}

export type ProjectUpsertPayload = {
  name: string
  codeOwners: string[]
}

export type RepositoryGitAuthMode = "NONE" | "BASIC" | "TOKEN"

export type RepositoryGitAuthPayload = {
  mode: RepositoryGitAuthMode
  username?: string | null
  password?: string | null
  token?: string | null
}

export type RepositoryResponse = {
  repoId: string
  projectId: string
  tenantId: string
  sourceMode: RepositorySourceMode
  remoteUrl?: string | null
  scmType?: RepositoryScmType | null
  defaultBranch?: string | null
  uploadKey?: string | null
  uploadChecksum?: string | null
  uploadSize?: number | null
  secretRef?: string | null
  gitAuthMode: RepositoryGitAuthMode
  gitAuthConfigured: boolean
  createdAt: string
  updatedAt?: string | null
}

export type ProjectRepositoryOverview = {
  total: number
  bySourceMode: Partial<Record<RepositorySourceMode, number>>
}

export type ProjectTaskOverview = {
  total: number
  running: number
  byStatus: Partial<Record<TaskStatus, number>>
  byType: Partial<Record<TaskType, number>>
}

export type ProjectFindingOverview = {
  open: number
  confirmed: number
  bySeverityUnresolved: Partial<Record<Severity, number>>
}

export type ProjectScaIssueOverview = {
  open: number
  confirmed: number
  bySeverityUnresolved: Partial<Record<Severity, number>>
}

export type ProjectOverviewResponse = {
  project: Project
  repositories: ProjectRepositoryOverview
  tasks: ProjectTaskOverview
  findings: ProjectFindingOverview
  sca: ProjectScaIssueOverview
}

export type RepositoryGitMetadata = {
  repoId: string
  defaultBranch?: string | null
  headCommit?: GitCommitSummary
  branches: GitRefSummary[]
  tags: GitRefSummary[]
  commits: GitCommitSummary[]
  fetchedAt: string
}

export type GitRefSummary = {
  name: string
  commitId: string
}

export type GitCommitSummary = {
  commitId: string
  shortMessage?: string | null
  authorName?: string | null
  authoredAt?: string | null
  ref?: string | null
}

export type RepositoryUpsertPayload = {
  projectId: string
  sourceMode: RepositorySourceMode
  remoteUrl?: string | null
  scmType?: RepositoryScmType | null
  defaultBranch?: string | null
  uploadKey?: string | null
  uploadChecksum?: string | null
  uploadSize?: number | null
  secretRef?: string | null
  gitAuth?: RepositoryGitAuthPayload | null
}

export type RepositoryUpdatePayload = {
  sourceMode: RepositorySourceMode
  remoteUrl?: string | null
  scmType?: RepositoryScmType | null
  defaultBranch?: string | null
  uploadKey?: string | null
  uploadChecksum?: string | null
  uploadSize?: number | null
  secretRef?: string | null
  gitAuth?: RepositoryGitAuthPayload | null
}

export type TaskSummary = {
  taskId: string
  tenantId: string
  projectId: string
  repoId?: string | null
  executorId?: string | null
  status: string
  type: TaskType
  name?: string | null
  correlationId: string
  owner?: string | null
  sourceRefType: SourceRefType
  sourceRef?: string | null
  commitSha?: string | null
  engine?: string | null
  semgrepProEnabled?: boolean
  createdAt: string
  updatedAt?: string | null
  spec: TaskSpecPayload
}

export type TaskStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELED"

export type TaskListFilters = {
  projectId?: string
  type?: TaskType
  excludeType?: TaskType
  status?: TaskStatus
  search?: string
  limit?: number
  offset?: number
}

export type FindingListFilters = {
  status?: FindingStatus
  severity?: string
  search?: string
  limit?: number
  offset?: number
}

export type TaskSpecPayload = {
  source: SourceSpecPayload
  ruleSelector: RuleSelectorPayload
  policy?: PolicySpecPayload | null
  ticket?: TicketPolicyPayload | null
  engineOptions?: EngineOptionsPayload | null
  aiReview?: AiReviewSpecPayload | null
  scaAiReview?: ScaAiReviewSpecPayload | null
}

export type AiReviewSpecPayload = {
  enabled: boolean
  mode: "simple" | "precise"
  dataFlowMode?: "simple" | "precise" | null
}

export type ScaAiReviewSpecPayload = {
  enabled: boolean
  critical: boolean
  high: boolean
  medium: boolean
  low: boolean
  info: boolean
}

export type SourceSpecPayload = {
  git?: GitSourcePayload | null
  archive?: ArchiveSourcePayload | null
  filesystem?: FilesystemSourcePayload | null
  image?: ImageSourcePayload | null
  sbom?: SbomSourcePayload | null
  url?: UrlSourcePayload | null
  baselineFingerprints?: string[] | null
  gitRefType?: SourceRefType | null
}

export type GitSourcePayload = {
  repo: string
  ref: string
  refType?: SourceRefType | null
}

export type ArchiveSourcePayload = {
  url?: string | null
  uploadId?: string | null
}

export type FilesystemSourcePayload = {
  path?: string | null
  uploadId?: string | null
}

export type ImageSourcePayload = {
  ref: string
}

export type SbomSourcePayload = {
  uploadId?: string | null
  url?: string | null
}

export type UrlSourcePayload = {
  url: string
}

export type UploadResponse = {
  uploadId: string
  fileName: string
  sizeBytes: number
  sha256: string
}

export type DependencyGraphNode = {
  id: string
  label: string
  purl?: string | null
  name?: string | null
  version?: string | null
}

export type DependencyGraphEdge = {
  source: string
  target: string
}

export type DependencyGraph = {
  nodes: DependencyGraphNode[]
  edges: DependencyGraphEdge[]
}

export type RuleSelectorPayload = {
  mode: string
  profile?: string | null
  explicitRules?: string[] | null
}

export type PolicySpecPayload = {
  failOn?: FailOnPolicyPayload | null
  scanPolicy?: ScanPolicyPayload | null
}

export type FailOnPolicyPayload = {
  severity: string
}

export type ScanPolicyPayload = {
  mode?: string | null
  parallelism?: number | null
  failStrategy?: string | null
}

export type TicketPolicyPayload = {
  project: string
  assigneeStrategy: string
  slaDays?: number | null
  labels?: string[]
}

export type EngineOptionsPayload = {
  semgrep?: SemgrepEngineOptionsPayload | null
}

export type SemgrepEngineOptionsPayload = {
  usePro?: boolean
  token?: string | null
}

export type CreateTaskPayload = {
  projectId: string
  repoId?: string | null
  executorId?: string | null
  type: TaskType
  name: string
  sourceRefType: SourceRefType
  sourceRef?: string | null
  commitSha?: string | null
  engine?: string | null
  spec: TaskSpecPayload
  owner?: string | null
}

export type ResultReviewRequest = {
  autoTicket?: boolean
  ticketProvider?: string
  labels?: string[]
  aiReviewEnabled?: boolean | null
  aiReviewMode?: "simple" | "precise" | null
  aiReviewDataFlowMode?: "simple" | "precise" | null
}

export type StageSummary = {
  stageId: string
  taskId: string
  type: string
  status: string
  artifacts: string[]
  startedAt?: string | null
  endedAt?: string | null
}

export type ArtifactSummary = {
  artifactId: string
  stageId: string
  uri: string
  kind: string
  checksum?: string | null
  sizeBytes?: number | null
}

export type FindingSummary = {
  findingId: string
  ruleId?: string | null
  sourceEngine: string
  severity: string
  status: string
  location: FindingLocationSummary
  introducedBy?: string | null
  hasDataFlow?: boolean
  review?: FindingReviewSummary | null
}

export type FindingLocationSummary = {
  path?: string | null
  line?: number | null
  startLine?: number | null
  startColumn?: number | null
  endColumn?: number | null
}

export type CodeLine = {
  lineNumber: number
  content: string
  highlight?: boolean
}

export type CodeSnippet = {
  path: string
  startLine: number
  endLine: number
  lines: CodeLine[]
}

export type DataFlowNode = {
  id: string
  label: string
  role?: string | null
  value?: string | null
  file?: string | null
  line?: number | null
  startColumn?: number | null
  endColumn?: number | null
}

export type DataFlowEdge = {
  source: string
  target: string
  label?: string | null
}

export type FindingDetail = {
  findingId: string
  taskId: string
  taskName?: string | null
  projectId: string
  projectName?: string | null
  repoId?: string | null
  repoRemoteUrl?: string | null
  ruleId?: string | null
  sourceEngine: string
  severity: string
  status: string
  location: FindingLocationSummary
  introducedBy?: string | null
  createdAt: string
  updatedAt?: string | null
  codeSnippet?: CodeSnippet | null
  dataFlowNodes: DataFlowNode[]
  dataFlowEdges: DataFlowEdge[]
  review?: FindingReviewSummary | null
}

export type ScaIssueSummary = {
  issueId: string
  vulnId: string
  sourceEngine: string
  severity: string
  status: FindingStatus
  packageName?: string | null
  installedVersion?: string | null
  fixedVersion?: string | null
  primaryUrl?: string | null
  componentPurl?: string | null
  componentName?: string | null
  componentVersion?: string | null
  introducedBy?: string | null
  createdAt: string
  updatedAt?: string | null
  review?: FindingReviewSummary | null
}

export type CvssSummary = {
  score: number
  vector?: string | null
  source?: string | null
}

export type ScaIssueDetail = {
  issueId: string
  taskId: string
  taskName?: string | null
  projectId: string
  projectName?: string | null
  vulnId: string
  sourceEngine: string
  title?: string | null
  description?: string | null
  references: string[]
  cvss?: CvssSummary | null
  severity: string
  status: FindingStatus
  packageName?: string | null
  installedVersion?: string | null
  fixedVersion?: string | null
  primaryUrl?: string | null
  componentPurl?: string | null
  componentName?: string | null
  componentVersion?: string | null
  introducedBy?: string | null
  createdAt: string
  updatedAt?: string | null
  review?: FindingReviewSummary | null
}

export type ScaIssueStatusUpdatePayload = {
  status: FindingStatus
}

export type ScaUsageEntry = {
  ecosystem?: string | null
  key?: string | null
  file?: string | null
  line?: number | null
  kind?: string | null
  snippet?: string | null
  language?: string | null
  symbol?: string | null
  receiver?: string | null
  callee?: string | null
  startLine?: number | null
  startCol?: number | null
  endLine?: number | null
  endCol?: number | null
  confidence?: number | null
}

export type TaskLogChunkResponse = {
  sequence: number
  stream: "stdout" | "stderr"
  content: string
  createdAt: string
  isLast: boolean
  stageId?: string | null
  stageType?: string | null
  level?: string
}

export type FindingStatus = "OPEN" | "CONFIRMED" | "FALSE_POSITIVE" | "RESOLVED" | "WONT_FIX"

export type FindingStatusUpdatePayload = {
  status: FindingStatus
  reason?: string | null
  fixVersion?: string | null
}

export type FindingBatchStatusUpdatePayload = {
  findingIds: string[]
  status: FindingStatus
  reason?: string | null
  fixVersion?: string | null
}

export type FindingBatchStatusUpdateFailure = {
  findingId: string
  error: string
}

export type FindingBatchStatusUpdateResponse = {
  updated: FindingSummary[]
  failed: FindingBatchStatusUpdateFailure[]
}

export type RuleResponse = {
  ruleId: string
  tenantId: string
  scope: string
  key: string
  name: string
  engine: string
  langs: string[]
  severityDefault: string
  tags: string[]
  enabled: boolean
  hash: string
  signature?: string | null
  createdAt: string
}

export type RuleUpsertPayload = {
  scope: string
  key: string
  name: string
  engine: string
  langs: string[]
  severityDefault: string
  tags: string[]
  pattern: Record<string, unknown>
  docs?: Record<string, unknown>
  enabled: boolean
  hash: string
  signature?: string | null
}

export type TicketStatus = "OPEN" | "SYNCED" | "FAILED"
export type TicketIssueType = "BUG" | "TASK" | "STORY"

export type TicketProviderPolicyDefaults = {
  project: string
  assigneeStrategy: string
  labels: string[]
}

export type TicketProviderTemplate = {
  provider: string
  name: string
  enabled: boolean
  defaultPolicy: TicketProviderPolicyDefaults
}

export type TicketDraftItemType = "FINDING" | "SCA_ISSUE"

export type TicketDraftItem = {
  type: TicketDraftItemType
  id: string
  title?: string | null
  severity: string
  status: FindingStatus
  location: Record<string, unknown>
}

export type TicketDraftDetail = {
  draftId: string
  projectId?: string | null
  provider?: string | null
  items: TicketDraftItem[]
  itemCount: number
  titleI18n?: Record<string, unknown> | null
  descriptionI18n?: Record<string, unknown> | null
  lastAiJobId?: string | null
  createdAt: string
  updatedAt?: string | null
}

export type TicketSummary = {
  ticketId: string
  projectId: string
  provider: string
  externalKey: string
  status: TicketStatus
  payload: Record<string, unknown>
  createdAt: string
  updatedAt?: string | null
}

export type TicketCreationPayload = {
  projectId: string
  provider: string
  findingIds: string[]
  ticketPolicy: {
    project: string
    assigneeStrategy: string
    slaDays?: number | null
    labels?: string[]
  }
  issueType?: TicketIssueType
  summary?: string | null
  severity: string
  labels: string[]
}

export type TicketCreateFromDraftPayload = {
  provider: string
  issueType?: TicketIssueType
  clearDraft?: boolean
}

export type TicketDraftItemsPayload = {
  items: Array<{ type: TicketDraftItemType; id: string }>
}

export type TicketDraftUpdatePayload = {
  provider?: string | null
  titleI18n?: Record<string, unknown> | null
  descriptionI18n?: Record<string, unknown> | null
}

export type TicketDraftAiPayload = {
  provider?: string | null
}

export type TicketDraftAiApplyPayload = {
  jobId: string
}

export type TicketStatusUpdatePayload = {
  status: TicketStatus
}

export type UserSummary = {
  id: string
  username: string
  email?: string | null
  enabled: boolean
}

export type UserListResponse = {
  items: UserSummary[]
  total: number
  limit: number
  offset: number
}

export type UserCreatePayload = {
  username: string
  email?: string | null
  password?: string | null
  enabled?: boolean
}

export type UserStatusUpdatePayload = {
  enabled: boolean
}

export type UserPasswordResetPayload = {
  password: string
  temporary?: boolean
}

export type AuthMeResponse = {
  userId: string
  tenantId: string
  username?: string | null
  email?: string | null
  roles: string[]
}

export type PermissionGroup = {
  group: string
  permissions: string[]
}

export type PermissionCatalogResponse = {
  permissions: string[]
  groups: PermissionGroup[]
}

export type IamRoleSummaryResponse = {
  roleId: string
  key: string
  name: string
  description?: string | null
  builtIn: boolean
  createdAt: string
  updatedAt?: string | null
}

export type IamRoleDetailResponse = {
  roleId: string
  key: string
  name: string
  description?: string | null
  builtIn: boolean
  permissions: string[]
  createdAt: string
  updatedAt?: string | null
}

export type IamRoleCreatePayload = {
  key: string
  name: string
  description?: string | null
  permissions?: string[]
}

export type IamRoleUpdatePayload = {
  name: string
  description?: string | null
}

export type IamRolePermissionsUpdatePayload = {
  permissions: string[]
}

export type UserDetailResponse = {
  userId: string
  tenantId: string
  username?: string | null
  email: string
  phone?: string | null
  name?: string | null
  enabled: boolean
  roleIds: string[]
  permissions: string[]
  lastLoginAt?: string | null
  createdAt: string
  updatedAt?: string | null
}

export type UserProfileUpdatePayload = {
  username?: string | null
  email?: string | null
  phone?: string | null
  name?: string | null
}

export type UserRoleAssignmentPayload = {
  roleIds: string[]
}

export type UserRoleAssignmentResponse = {
  userId: string
  roleIds: string[]
  permissions: string[]
}

export type TenantResponse = {
  tenantId: string
  name: string
  plan: string
  contactEmail?: string | null
  createdAt: string
  updatedAt?: string | null
}

export type TenantUpdatePayload = {
  name: string
  contactEmail?: string | null
}

export type AiClientConfig = {
  configId: string
  name: string
  provider: string
  baseUrl: string
  model: string
  isDefault: boolean
  enabled: boolean
  createdAt: string
  updatedAt?: string | null
}

export type AiClientConfigPayload = {
  name: string
  provider: string
  baseUrl: string
  apiKey?: string | null
  model: string
  isDefault: boolean
  enabled: boolean
}

export type AiProviderTemplate = {
  provider: string
  name: string
  baseUrl: string
  defaultModel: string
  regions: string[]
  models: string[]
  docsUrl?: string | null
  description?: string | null
}

export type AiMcpConfig = {
  profileId: string
  name: string
  type: string
  endpoint?: string | null
  entrypoint?: string | null
  params: Record<string, unknown>
  enabled: boolean
  createdAt: string
  updatedAt?: string | null
}

export type AiMcpConfigPayload = {
  name: string
  type: string
  endpoint?: string | null
  entrypoint?: string | null
  params?: Record<string, unknown> | null
  enabled: boolean
}

export type AiMcpUploadPayload = {
  name: string
  entrypoint?: string | null
  params?: Record<string, unknown> | null
  enabled: boolean
  file: File
}

export type AiAgentConfig = {
  agentId: string
  name: string
  kind: string
  entrypoint?: string | null
  params: Record<string, unknown>
  stageTypes: string[]
  mcpProfileId?: string | null
  enabled: boolean
  createdAt: string
  updatedAt?: string | null
}

export type AiAgentConfigPayload = {
  name: string
  kind: string
  entrypoint?: string | null
  params?: Record<string, unknown> | null
  stageTypes: string[]
  mcpProfileId?: string | null
  enabled: boolean
}

export type AiAgentUploadPayload = {
  name: string
  kind: string
  entrypoint?: string | null
  stageTypes: string[]
  mcpProfileId?: string | null
  enabled: boolean
  params?: Record<string, unknown> | null
  file: File
}

export type AiKnowledgeEntry = {
  entryId: string
  title: string
  body: string
  tags: string[]
  sourceUri?: string | null
  embedding?: number[] | null
  createdAt: string
  updatedAt: string
}

export type AiKnowledgeEntryPayload = {
  title: string
  body: string
  tags?: string[]
  sourceUri?: string | null
  embedding?: number[] | null
}

export type AiKnowledgeSearchPayload = {
  query: string
  limit?: number
  tags?: string[]
}

export type AiKnowledgeSearchHit = {
  entryId: string
  title: string
  snippet: string
  score: number
  sourceUri?: string | null
  tags: string[]
}


export type ExecutorStatus = "REGISTERED" | "READY" | "BUSY" | "DRAINING" | "OFFLINE"

export type ExecutorSummary = {
  executorId: string
  name: string
  status: ExecutorStatus
  labels: Record<string, string>
  cpuCapacity: number
  memoryCapacityMb: number
  cpuUsage?: number | null
  memoryUsageMb?: number | null
  lastHeartbeat?: string | null
  createdAt: string
}

export type ExecutorRegisterPayload = {
  name: string
  labels?: Record<string, string>
  cpuCapacity: number
  memoryCapacityMb: number
}

export type ExecutorTokenResponse = {
  executorId: string
  token: string
}

export type TaskAssignPayload = {
  executorId: string
}
