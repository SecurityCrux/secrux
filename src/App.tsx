import { createBrowserRouter, RouterProvider } from "react-router-dom"
import { Suspense } from "react"

import { AuthLayout } from "@/components/layout/auth-layout"
import { DashboardLayout } from "@/components/layout/dashboard-layout"
import { ProtectedRoute } from "@/components/routes/protected-route"
import { LoginPage } from "@/pages/auth/login"
import { DashboardOverview } from "@/pages/dashboard/overview"
import { Spinner } from "@/components/ui/spinner"
import { ProjectsPage } from "@/pages/projects/projects-page"
import { ProjectDetailPage } from "@/pages/projects/project-detail-page"
import { TaskExplorerPage } from "@/pages/tasks/task-explorer-page"
import { RuleListPage } from "@/pages/rules/rule-list-page"
import { FindingManagementPage } from "@/pages/findings/finding-management-page"
import { FindingDetailPage } from "@/pages/findings/finding-detail-page"
import { TicketManagementPage } from "@/pages/tickets/ticket-management-page"
import { TicketDetailPage } from "@/pages/tickets/ticket-detail-page"
import { AiClientManagementPage } from "@/pages/ai/ai-client-management-page"
import { AiKnowledgePage } from "@/pages/ai/ai-knowledge-page"
import { ScaManagementPage } from "@/pages/sca/sca-management-page"
import { ScaIssueDetailPage } from "@/pages/sca/sca-issue-detail-page"
import { ExecutorManagementPage } from "@/pages/executors/executor-management-page"
import { UserManagementPage } from "@/pages/users/user-management-page"
import { RoleManagementPage } from "@/pages/iam/role-management-page"
import { TenantPage } from "@/pages/tenant/tenant-page"
import { FirstRunSetup } from "@/components/setup/first-run-setup"
import { IntellijPluginPage } from "@/pages/ide-plugins/intellij-plugin-page"

const router = createBrowserRouter([
  {
    path: "/login",
    element: (
      <AuthLayout>
        <LoginPage />
      </AuthLayout>
    ),
  },
  {
    path: "/",
    element: (
      <ProtectedRoute>
        <DashboardLayout />
      </ProtectedRoute>
    ),
    children: [
      {
        index: true,
        element: <DashboardOverview />,
      },
      {
        path: "projects",
        element: <ProjectsPage />,
      },
      {
        path: "projects/:projectId",
        element: <ProjectDetailPage />,
      },
      {
        path: "tasks/explorer",
        element: <TaskExplorerPage />,
      },
      {
        path: "ide-plugins/intellij",
        element: <IntellijPluginPage />,
      },
      {
        path: "rules",
        element: <RuleListPage />,
      },
      {
        path: "findings",
        element: <FindingManagementPage />,
      },
      {
        path: "findings/:findingId",
        element: <FindingDetailPage />,
      },
      {
        path: "tickets",
        element: <TicketManagementPage />,
      },
      {
        path: "tickets/:ticketId",
        element: <TicketDetailPage />,
      },
      {
        path: "sca",
        element: <ScaManagementPage />,
      },
      {
        path: "sca/issues/:issueId",
        element: <ScaIssueDetailPage />,
      },
      {
        path: "ai-clients",
        element: <AiClientManagementPage />,
      },
      {
        path: "ai-knowledge",
        element: <AiKnowledgePage />,
      },
      {
        path: "executors",
        element: <ExecutorManagementPage />,
      },
      {
        path: "users",
        element: <UserManagementPage />,
      },
      {
        path: "iam/roles",
        element: <RoleManagementPage />,
      },
      {
        path: "tenant",
        element: <TenantPage />,
      },
    ],
  },
  {
    path: "*",
    element: <div className="flex min-h-screen items-center justify-center text-muted-foreground">Not found</div>,
  },
])

export function App() {
  return (
    <Suspense
      fallback={
        <div className="flex min-h-screen items-center justify-center">
          <Spinner className="text-muted-foreground" size={28} />
        </div>
      }
    >
      <>
        <RouterProvider router={router} />
        <FirstRunSetup />
      </>
    </Suspense>
  )
}
