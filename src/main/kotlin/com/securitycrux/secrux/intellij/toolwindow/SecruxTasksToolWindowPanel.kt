package com.securitycrux.secrux.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.i18n.SecruxI18nListener
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettings
import com.securitycrux.secrux.intellij.settings.SecruxTokenStore
import com.securitycrux.secrux.intellij.secrux.SecruxApiClient
import com.securitycrux.secrux.intellij.util.GitCli
import com.securitycrux.secrux.intellij.util.GitCommitSummary
import com.securitycrux.secrux.intellij.util.GitRemoteResolver
import com.securitycrux.secrux.intellij.util.GitUrlNormalizer
import com.securitycrux.secrux.intellij.util.SecruxNotifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.UUID
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class SecruxTasksToolWindowPanel(
    private val project: Project,
) : SimpleToolWindowPanel(/* vertical = */ true, /* borderless = */ true) {

    private val settings = SecruxProjectSettings.getInstance(project)

    private val searchField = JBTextField()
    private val statusCombo = JComboBox(StatusFilterItem.entries.toTypedArray())
    private val typeCombo = JComboBox(TypeFilterItem.entries.toTypedArray())
    private val pageLabel = JBLabel()

    private val refreshButton = JButton()
    private val prevButton = JButton()
    private val nextButton = JButton()
    private val useSelectedButton = JButton()

    private val reloadProjectsButton = JButton()
    private val projectCombo = JComboBox<ProjectOption>()
    private val repoCombo = JComboBox<RepositoryOption>()
    private val nameField = JBTextField()
    private val reloadGitButton = JButton()
    private val branchCombo = JComboBox<BranchOption>()
    private val commitCombo = JComboBox<CommitOption>()
    private val createButton = JButton()
    private val setAsDefaultCheckbox = com.intellij.ui.components.JBCheckBox()

    private val createStatusLabel = JBLabel()

    private var lastRepoMatchPromptProjectId: String? = null
    private var lastRepoMatchPromptedNormalizedRemote: String? = null
    private var isUpdatingProjectCombo = false
    private var isUpdatingBranchCombo = false

    private val tableModel = TaskTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = true
    }

    private var limit = 50
    private var offset = 0
    private var total = 0L

    init {
        searchField.columns = 20
        searchField.toolTipText = SecruxBundle.message("tooltip.search")
        searchField.addActionListener {
            offset = 0
            reload()
        }

        statusCombo.addActionListener {
            offset = 0
            reload()
        }
        typeCombo.addActionListener {
            offset = 0
            reload()
        }

        refreshButton.addActionListener { reload() }
        prevButton.addActionListener {
            offset = (offset - limit).coerceAtLeast(0)
            reload()
        }
        nextButton.addActionListener {
            offset = (offset + limit).coerceAtMost(maxOffset())
            reload()
        }

        useSelectedButton.addActionListener {
            val task = selectedTask()
            if (task == null) {
                Messages.showInfoMessage(project, SecruxBundle.message("message.selectTask.noneSelected"), SecruxBundle.message("dialog.title"))
                return@addActionListener
            }
            settings.state.taskId = task.taskId
            SecruxNotifications.info(project, SecruxBundle.message("message.taskSetAsDefault", task.taskId))
        }

        projectCombo.model = DefaultComboBoxModel(arrayOf(ProjectOption.Select))
        repoCombo.model = DefaultComboBoxModel(arrayOf(RepositoryOption.Unset))
        repoCombo.isEnabled = false

        branchCombo.model = DefaultComboBoxModel(arrayOf(BranchOption.Loading))
        branchCombo.isEnabled = false
        commitCombo.model = DefaultComboBoxModel(arrayOf(CommitOption.Loading))
        commitCombo.isEnabled = false

        reloadProjectsButton.addActionListener {
            lastRepoMatchPromptProjectId = null
            lastRepoMatchPromptedNormalizedRemote = null
            reloadProjects()
        }
        projectCombo.addActionListener { onProjectSelectionChanged() }

        reloadGitButton.addActionListener { reloadGitInfo(resetSelection = true) }
        branchCombo.addActionListener { onBranchSelectionChanged() }

        createButton.addActionListener { createIdeTask() }

        val controls =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 0, 6, 0)
            }
        controls.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel(SecruxBundle.message("label.search")))
                add(searchField)
                add(JBLabel(SecruxBundle.message("label.status")))
                add(statusCombo)
                add(JBLabel(SecruxBundle.message("label.taskType")))
                add(typeCombo)
                add(refreshButton)
                add(useSelectedButton)
            },
            BorderLayout.WEST,
        )
        controls.add(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                add(prevButton)
                add(nextButton)
                add(pageLabel)
            },
            BorderLayout.EAST,
        )

        val createPanel =
            JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(6))).apply {
                border = JBUI.Borders.empty(8, 0, 0, 0)
            }
        createPanel.add(JBLabel(SecruxBundle.message("section.ideTask")))
        createPanel.add(JBLabel(SecruxBundle.message("label.project")))
        createPanel.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(projectCombo)
                add(reloadProjectsButton)
            },
        )
        createPanel.add(JBLabel(SecruxBundle.message("label.repositoryOptional")))
        createPanel.add(repoCombo.apply { toolTipText = SecruxBundle.message("tooltip.repositoryOptional") })
        createPanel.add(JBLabel(SecruxBundle.message("label.taskNameOptional")))
        createPanel.add(nameField)
        createPanel.add(JBLabel(SecruxBundle.message("label.branchOptional")))
        createPanel.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(branchCombo)
                add(reloadGitButton)
            },
        )
        createPanel.add(JBLabel(SecruxBundle.message("label.commitOptional")))
        createPanel.add(commitCombo)
        createPanel.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(setAsDefaultCheckbox)
                add(createButton)
            },
        )
        createPanel.add(createStatusLabel)

        val tableScroll = JBScrollPane(table).apply { preferredSize = JBUI.size(0, 240) }
        val root =
            JPanel(BorderLayout()).apply {
                add(controls, BorderLayout.NORTH)
                add(tableScroll, BorderLayout.CENTER)
                add(createPanel, BorderLayout.SOUTH)
                minimumSize = Dimension(640, 260)
            }

        setContent(JBScrollPane(root))

        refreshTexts()
        reloadProjects()
        reloadGitInfo(resetSelection = false)
        reload()
    }

    fun bind(disposable: Disposable) {
        ApplicationManager.getApplication()
            .messageBus
            .connect(disposable)
            .subscribe(
                SecruxI18nListener.TOPIC,
                SecruxI18nListener {
                    refreshTexts()
                },
            )
    }

    private fun refreshTexts() {
        refreshButton.text = SecruxBundle.message("action.refresh")
        prevButton.text = SecruxBundle.message("action.prevPage")
        nextButton.text = SecruxBundle.message("action.nextPage")
        useSelectedButton.text = SecruxBundle.message("action.useSelectedTask")
        reloadProjectsButton.text = SecruxBundle.message("action.reloadProjects")
        reloadGitButton.text = SecruxBundle.message("action.reloadGit")
        createButton.text = SecruxBundle.message("action.createIdeTask")
        setAsDefaultCheckbox.text = SecruxBundle.message("label.setAsDefaultTask")
        setAsDefaultCheckbox.isSelected = true
        tableModel.refreshColumns()
        projectCombo.repaint()
        repoCombo.repaint()
        branchCombo.repaint()
        commitCombo.repaint()
    }

    private fun selectedTask(): TaskRow? {
        val viewRow = table.selectedRow
        val modelRow = if (viewRow >= 0) table.convertRowIndexToModel(viewRow) else -1
        return tableModel.getAt(modelRow)
    }

    private fun onProjectSelectionChanged() {
        if (isUpdatingProjectCombo) return
        val selected = projectCombo.selectedItem as? ProjectOption.Item
        if (selected == null) {
            repoCombo.model = DefaultComboBoxModel(arrayOf(RepositoryOption.Unset))
            repoCombo.isEnabled = false
            return
        }
        reloadRepositories(selected)
    }

    private fun onBranchSelectionChanged() {
        if (isUpdatingBranchCombo) return
        val branch = (branchCombo.selectedItem as? BranchOption.Item)?.name
        reloadCommits(ref = branch ?: "HEAD")
    }

    private fun reloadGitInfo(resetSelection: Boolean) {
        val previousBranch = (branchCombo.selectedItem as? BranchOption.Item)?.name
        branchCombo.model = DefaultComboBoxModel(arrayOf(BranchOption.Loading))
        branchCombo.isEnabled = false
        commitCombo.model = DefaultComboBoxModel(arrayOf(CommitOption.Loading))
        commitCombo.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.loadingGitInfo"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val currentBranch = GitCli.currentBranch(project)
                    val branches = GitCli.listBranches(project)
                    val selectedBranch =
                        if (resetSelection) {
                            currentBranch
                        } else {
                            previousBranch ?: currentBranch
                        }

                    val ref = selectedBranch ?: "HEAD"
                    val commits = GitCli.listCommits(project, ref, limit = 50)
                    val headCommitId = GitCli.headCommit(project)

                    ApplicationManager.getApplication().invokeLater {
                        applyBranchOptions(branches, currentBranch, selectedBranch)
                        applyCommitOptions(commits, preferredCommitId = headCommitId)
                        createStatusLabel.text = ""
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        branchCombo.model = DefaultComboBoxModel(arrayOf(BranchOption.Unset))
                        branchCombo.isEnabled = true
                        commitCombo.model = DefaultComboBoxModel(arrayOf(CommitOption.Unset))
                        commitCombo.isEnabled = true
                        createStatusLabel.text = SecruxBundle.message("error.loadingGitInfo", message)
                    }
                }
            },
        )
    }

    private fun reloadCommits(ref: String) {
        val previousCommitId = (commitCombo.selectedItem as? CommitOption.Item)?.commitId
        commitCombo.model = DefaultComboBoxModel(arrayOf(CommitOption.Loading))
        commitCombo.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.loadingGitCommits"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val commits = GitCli.listCommits(project, ref, limit = 50)
                    val headCommitId = GitCli.headCommit(project)
                    ApplicationManager.getApplication().invokeLater {
                        applyCommitOptions(commits, preferredCommitId = previousCommitId ?: headCommitId)
                        createStatusLabel.text = ""
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        commitCombo.model = DefaultComboBoxModel(arrayOf(CommitOption.Unset))
                        commitCombo.isEnabled = true
                        createStatusLabel.text = SecruxBundle.message("error.loadingGitCommits", message)
                    }
                }
            },
        )
    }

    private fun applyBranchOptions(branches: List<String>, currentBranch: String?, selectedBranch: String?) {
        isUpdatingBranchCombo = true
        val items = mutableListOf<BranchOption>()
        items.add(BranchOption.Unset)
        for (branch in branches) {
            items.add(BranchOption.Item(name = branch, isCurrent = branch == currentBranch))
        }
        branchCombo.model = DefaultComboBoxModel(items.toTypedArray())
        branchCombo.isEnabled = true
        val selectedOption = items.filterIsInstance<BranchOption.Item>().firstOrNull { it.name == selectedBranch }
        branchCombo.selectedItem = selectedOption ?: BranchOption.Unset
        isUpdatingBranchCombo = false
    }

    private fun applyCommitOptions(commits: List<GitCommitSummary>, preferredCommitId: String?) {
        val items = mutableListOf<CommitOption>()
        items.add(CommitOption.Unset)
        for (commit in commits) {
            items.add(CommitOption.Item(commitId = commit.commitId, subject = commit.subject))
        }
        commitCombo.model = DefaultComboBoxModel(items.toTypedArray())
        commitCombo.isEnabled = true

        val preferred = preferredCommitId?.trim()
        val preferredOption =
            if (!preferred.isNullOrBlank()) {
                items.filterIsInstance<CommitOption.Item>().firstOrNull { it.commitId.equals(preferred, ignoreCase = true) }
            } else {
                null
            }
        commitCombo.selectedItem = preferredOption ?: items.filterIsInstance<CommitOption.Item>().firstOrNull() ?: CommitOption.Unset
    }

    private fun reloadProjects() {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            createStatusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.loadingProjects"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            createStatusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }

                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    val response = client.listProjects()
                    val projects = parseProjectList(response)
                    ApplicationManager.getApplication().invokeLater {
                        applyProjectOptions(projects)
                        createStatusLabel.text = ""
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        createStatusLabel.text = SecruxBundle.message("error.loadingProjects", message)
                    }
                }
            },
        )
    }

    private fun reloadRepositories(selectedProject: ProjectOption.Item) {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            createStatusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }

        repoCombo.model = DefaultComboBoxModel(arrayOf(RepositoryOption.Loading))
        repoCombo.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.loadingRepositories"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            createStatusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }

                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    val response = client.listRepositories(selectedProject.projectId)
                    val repositories = parseRepositoryList(response)

                    val localRemote = GitRemoteResolver.resolvePrimaryRemote(project)
                    val matchedRepository =
                        if (localRemote != null) {
                            repositories.firstOrNull { repo ->
                                val remote = repo.remoteUrl ?: return@firstOrNull false
                                GitUrlNormalizer.normalize(remote) == localRemote.normalizedUrl
                            }
                        } else {
                            null
                        }

                    ApplicationManager.getApplication().invokeLater {
                        applyRepositoryOptions(
                            selectedProject = selectedProject,
                            repositories = repositories,
                            localRemoteNormalizedUrl = localRemote?.normalizedUrl,
                            matchedRepository = matchedRepository,
                        )
                        createStatusLabel.text = ""
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        repoCombo.model = DefaultComboBoxModel(arrayOf(RepositoryOption.Unset))
                        repoCombo.isEnabled = false
                        createStatusLabel.text = SecruxBundle.message("error.loadingRepositories", message)
                    }
                }
            },
        )
    }

    private fun applyProjectOptions(projects: List<ProjectRow>) {
        val previousProjectId = (projectCombo.selectedItem as? ProjectOption.Item)?.projectId
        isUpdatingProjectCombo = true
        val model = DefaultComboBoxModel<ProjectOption>()
        model.addElement(ProjectOption.Select)
        for (project in projects) {
            model.addElement(ProjectOption.Item(project.projectId, project.name))
        }
        projectCombo.model = model

        val toSelect =
            projects.firstOrNull { it.projectId == previousProjectId }
                ?.let { ProjectOption.Item(it.projectId, it.name) }
                ?: if (projects.size == 1) ProjectOption.Item(projects.first().projectId, projects.first().name) else ProjectOption.Select

        projectCombo.selectedItem = toSelect
        repoCombo.model = DefaultComboBoxModel(arrayOf(RepositoryOption.Unset))
        repoCombo.isEnabled = false
        isUpdatingProjectCombo = false
        onProjectSelectionChanged()
    }

    private fun applyRepositoryOptions(
        selectedProject: ProjectOption.Item,
        repositories: List<RepositoryRow>,
        localRemoteNormalizedUrl: String?,
        matchedRepository: RepositoryRow?,
    ) {
        val currentProjectId = (projectCombo.selectedItem as? ProjectOption.Item)?.projectId
        if (currentProjectId != selectedProject.projectId) return

        val previousRepoId = (repoCombo.selectedItem as? RepositoryOption.Item)?.repoId

        val model = DefaultComboBoxModel<RepositoryOption>()
        model.addElement(RepositoryOption.Unset)
        for (repo in repositories) {
            model.addElement(RepositoryOption.Item(repo.repoId, repo.remoteUrl))
        }
        repoCombo.model = model
        repoCombo.isEnabled = true

        val selectedRepo =
            previousRepoId?.let { repoId ->
                repositories.firstOrNull { it.repoId == repoId }?.let { RepositoryOption.Item(it.repoId, it.remoteUrl) }
            } ?: RepositoryOption.Unset
        repoCombo.selectedItem = selectedRepo

        maybePromptUseMatchedRepository(
            selectedProject = selectedProject,
            localRemoteNormalizedUrl = localRemoteNormalizedUrl,
            matchedRepository = matchedRepository,
        )
    }

    private fun maybePromptUseMatchedRepository(
        selectedProject: ProjectOption.Item,
        localRemoteNormalizedUrl: String?,
        matchedRepository: RepositoryRow?,
    ) {
        if (matchedRepository == null) return
        if (localRemoteNormalizedUrl.isNullOrBlank()) return
        if (repoCombo.selectedItem !is RepositoryOption.Unset) return

        if (lastRepoMatchPromptProjectId == selectedProject.projectId && lastRepoMatchPromptedNormalizedRemote == localRemoteNormalizedUrl) {
            return
        }
        lastRepoMatchPromptProjectId = selectedProject.projectId
        lastRepoMatchPromptedNormalizedRemote = localRemoteNormalizedUrl

        val remoteUrl = matchedRepository.remoteUrl ?: return
        val message = SecruxBundle.message("message.repositoryMatchFound", selectedProject.name, remoteUrl)
        val answer = Messages.showYesNoDialog(project, message, SecruxBundle.message("dialog.title"), Messages.getQuestionIcon())
        if (answer != Messages.YES) return

        repoCombo.selectedItem = RepositoryOption.Item(matchedRepository.repoId, matchedRepository.remoteUrl)
    }

    private fun reload() {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            createStatusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.loadingTasks"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            createStatusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }

                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    val response =
                        client.listTasks(
                            limit = limit,
                            offset = offset,
                            search = searchField.text.trim().ifBlank { null },
                            status = (statusCombo.selectedItem as? StatusFilterItem)?.status,
                            projectId = null,
                            type = (typeCombo.selectedItem as? TypeFilterItem)?.taskType,
                        )

                    val page = parseTaskPage(response)
                    ApplicationManager.getApplication().invokeLater {
                        total = page.total
                        limit = page.limit
                        offset = page.offset
                        tableModel.setTasks(page.items)
                        pageLabel.text = SecruxBundle.message("label.pageInfo", offset, limit, total)
                        createStatusLabel.text = ""
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        createStatusLabel.text = SecruxBundle.message("error.loadingTasks", message)
                    }
                }
            },
        )
    }

    private fun createIdeTask() {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            createStatusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }

        val selectedProject = projectCombo.selectedItem as? ProjectOption.Item
        if (selectedProject == null) {
            createStatusLabel.text = SecruxBundle.message("error.projectRequired")
            return
        }
        val projectIdText = selectedProject.projectId.trim()
        if (runCatching { UUID.fromString(projectIdText) }.isFailure) {
            createStatusLabel.text = SecruxBundle.message("error.projectIdInvalid")
            return
        }

        val request =
            buildJsonObject {
                put("projectId", projectIdText)
                val selectedRepo = repoCombo.selectedItem as? RepositoryOption.Item
                if (selectedRepo != null) {
                    put("repoId", selectedRepo.repoId)
                }
                nameField.text.trim().takeIf { it.isNotBlank() }?.let { put("name", it) }
                val branch = (branchCombo.selectedItem as? BranchOption.Item)?.name?.trim()?.takeIf { it.isNotBlank() }
                if (branch != null) put("branch", branch)
                val commitSha = (commitCombo.selectedItem as? CommitOption.Item)?.commitId?.trim()?.takeIf { it.isNotBlank() }
                if (commitSha != null) put("commitSha", commitSha)
            }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.creatingIdeTask"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            createStatusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }

                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    val response = client.createIdeAuditTask(request.toString())
                    val task = parseCreatedTask(response)
                    ApplicationManager.getApplication().invokeLater {
                        createStatusLabel.text = SecruxBundle.message("message.ideTaskCreated", task.taskId)
                        if (setAsDefaultCheckbox.isSelected) {
                            settings.state.taskId = task.taskId
                        }
                        reload()
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        createStatusLabel.text = SecruxBundle.message("error.createIdeTaskFailed", message)
                    }
                }
            },
        )
    }

    private fun maxOffset(): Int {
        val remaining = (total - limit).coerceAtLeast(0)
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private data class TaskPage(
        val items: List<TaskRow>,
        val total: Long,
        val limit: Int,
        val offset: Int,
    ) {
        companion object {
            fun empty(): TaskPage = TaskPage(items = emptyList(), total = 0, limit = 50, offset = 0)
        }
    }

    private data class TaskRow(
        val taskId: String,
        val name: String?,
        val status: String?,
        val type: String?,
        val createdAt: String?,
    )

    private data class ProjectRow(
        val projectId: String,
        val name: String,
    )

    private data class RepositoryRow(
        val repoId: String,
        val remoteUrl: String?,
    )

    private sealed class BranchOption {
        data object Loading : BranchOption() {
            override fun toString(): String = SecruxBundle.message("combo.loading")
        }

        data object Unset : BranchOption() {
            override fun toString(): String = SecruxBundle.message("combo.branch.unset")
        }

        data class Item(
            val name: String,
            val isCurrent: Boolean,
        ) : BranchOption() {
            override fun toString(): String =
                if (isCurrent) {
                    SecruxBundle.message("combo.branch.current", name)
                } else {
                    name
                }
        }
    }

    private sealed class CommitOption {
        data object Loading : CommitOption() {
            override fun toString(): String = SecruxBundle.message("combo.loading")
        }

        data object Unset : CommitOption() {
            override fun toString(): String = SecruxBundle.message("combo.commit.unset")
        }

        data class Item(
            val commitId: String,
            val subject: String?,
        ) : CommitOption() {
            override fun toString(): String =
                buildString {
                    append(commitId.take(10))
                    if (!subject.isNullOrBlank()) {
                        append("  ")
                        append(subject)
                    }
                }
        }
    }

    private sealed class ProjectOption {
        data object Select : ProjectOption() {
            override fun toString(): String = SecruxBundle.message("combo.project.select")
        }

        data class Item(
            val projectId: String,
            val name: String,
        ) : ProjectOption() {
            override fun toString(): String = "$name ($projectId)"
        }
    }

    private sealed class RepositoryOption {
        data object Loading : RepositoryOption() {
            override fun toString(): String = SecruxBundle.message("combo.loading")
        }

        data object Unset : RepositoryOption() {
            override fun toString(): String = SecruxBundle.message("combo.repository.unset")
        }

        data class Item(
            val repoId: String,
            val remoteUrl: String?,
        ) : RepositoryOption() {
            override fun toString(): String = remoteUrl ?: repoId
        }
    }

    private class TaskTableModel : AbstractTableModel() {

        private val tasks = mutableListOf<TaskRow>()
        private var columns = buildColumns()

        fun refreshColumns() {
            columns = buildColumns()
            fireTableStructureChanged()
        }

        fun setTasks(newTasks: List<TaskRow>) {
            tasks.clear()
            tasks.addAll(newTasks)
            fireTableDataChanged()
        }

        fun getAt(row: Int): TaskRow? = tasks.getOrNull(row)

        override fun getRowCount(): Int = tasks.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns.getOrElse(column) { "" }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = tasks.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> row.name ?: ""
                1 -> row.status ?: ""
                2 -> row.type ?: ""
                3 -> row.createdAt ?: ""
                4 -> row.taskId
                else -> ""
            }
        }

        private fun buildColumns(): List<String> =
            listOf(
                SecruxBundle.message("table.task.column.name"),
                SecruxBundle.message("table.task.column.status"),
                SecruxBundle.message("table.task.column.type"),
                SecruxBundle.message("table.task.column.createdAt"),
                SecruxBundle.message("table.task.column.taskId"),
            )
    }

    private fun parseTaskPage(body: String): TaskPage {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(body).jsonObject

        val success = root.booleanOrNull("success") ?: true
        if (!success) {
            val message = root.stringOrNull("message") ?: "unknown error"
            throw IllegalStateException(message)
        }

        val data = root["data"]?.jsonObject ?: return TaskPage.empty()
        val items = data["items"]?.jsonArray ?: JsonArray(emptyList())

        val tasks =
            items.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val taskId = obj.stringOrNull("taskId") ?: return@mapNotNull null
                TaskRow(
                    taskId = taskId,
                    name = obj.stringOrNull("name"),
                    status = obj.stringOrNull("status"),
                    type = obj.stringOrNull("type"),
                    createdAt = obj.stringOrNull("createdAt"),
                )
            }

        val total = data.longOrNull("total") ?: tasks.size.toLong()
        val limit = data.intOrNull("limit") ?: this.limit
        val offset = data.intOrNull("offset") ?: this.offset

        return TaskPage(items = tasks, total = total, limit = limit, offset = offset)
    }

    private fun parseCreatedTask(body: String): TaskRow {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(body).jsonObject

        val success = root.booleanOrNull("success") ?: true
        if (!success) {
            val message = root.stringOrNull("message") ?: "unknown error"
            throw IllegalStateException(message)
        }

        val obj = root["data"]?.jsonObject ?: throw IllegalStateException("missing task data")
        val taskId = obj.stringOrNull("taskId") ?: throw IllegalStateException("missing taskId")
        return TaskRow(
            taskId = taskId,
            name = obj.stringOrNull("name"),
            status = obj.stringOrNull("status"),
            type = obj.stringOrNull("type"),
            createdAt = obj.stringOrNull("createdAt"),
        )
    }

    private fun parseProjectList(body: String): List<ProjectRow> {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(body).jsonObject

        val success = root.booleanOrNull("success") ?: true
        if (!success) {
            val message = root.stringOrNull("message") ?: "unknown error"
            throw IllegalStateException(message)
        }

        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val projectId = obj.stringOrNull("projectId") ?: return@mapNotNull null
            val name = obj.stringOrNull("name") ?: projectId
            ProjectRow(projectId = projectId, name = name)
        }
    }

    private fun parseRepositoryList(body: String): List<RepositoryRow> {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(body).jsonObject

        val success = root.booleanOrNull("success") ?: true
        if (!success) {
            val message = root.stringOrNull("message") ?: "unknown error"
            throw IllegalStateException(message)
        }

        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val repoId = obj.stringOrNull("repoId") ?: return@mapNotNull null
            RepositoryRow(repoId = repoId, remoteUrl = obj.stringOrNull("remoteUrl"))
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toLongOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toBooleanStrictOrNull()

    private enum class StatusFilterItem(
        val status: String?,
    ) {
        ALL(null),
        PENDING("PENDING"),
        RUNNING("RUNNING"),
        SUCCEEDED("SUCCEEDED"),
        FAILED("FAILED"),
        CANCELED("CANCELED");

        override fun toString(): String =
            status ?: SecruxBundle.message("filter.status.all")
    }

    private enum class TypeFilterItem(
        val taskType: String?,
    ) {
        ALL(null),
        CODE_CHECK("CODE_CHECK"),
        IDE_AUDIT("IDE_AUDIT");

        override fun toString(): String =
            taskType ?: SecruxBundle.message("filter.type.all")
    }
}
