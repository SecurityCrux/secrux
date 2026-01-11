import {
  LayoutDashboard,
  FolderGit2,
  Workflow,
  ShieldCheck,
  Bug,
  Boxes,
  Ticket,
  Bot,
  ServerCog,
  BookOpen,
  Users,
  KeyRound,
  Building2,
} from "lucide-react"

export type NavItem = {
  labelKey: string
  href: string
  icon: React.ComponentType<{ className?: string }>
  requiredRoles?: string[]
  children?: NavItem[]
}

export const navItems: NavItem[] = [
  {
    labelKey: "nav.dashboard",
    href: "/",
    icon: LayoutDashboard,
  },
  {
    labelKey: "nav.projects",
    href: "/projects",
    icon: FolderGit2,
  },
  {
    labelKey: "nav.tasks",
    href: "/tasks/explorer",
    icon: Workflow,
  },
  {
    labelKey: "nav.rules",
    href: "/rules",
    icon: ShieldCheck,
  },
  {
    labelKey: "nav.findings",
    href: "/findings",
    icon: Bug,
  },
  {
    labelKey: "nav.sca",
    href: "/sca",
    icon: Boxes,
  },
  {
    labelKey: "nav.tickets",
    href: "/tickets",
    icon: Ticket,
  },
  {
    labelKey: "nav.aiClients",
    href: "/ai-clients",
    icon: Bot,
  },
  {
    labelKey: "nav.aiKnowledge",
    href: "/ai-knowledge",
    icon: BookOpen,
  },
  {
    labelKey: "nav.executors",
    href: "/executors",
    icon: ServerCog,
  },
  {
    labelKey: "nav.users",
    href: "/users",
    icon: Users,
    requiredRoles: ["user:manage"],
    children: [
      {
        labelKey: "nav.roles",
        href: "/iam/roles",
        icon: KeyRound,
        requiredRoles: ["user:manage"],
      },
      {
        labelKey: "nav.tenant",
        href: "/tenant",
        icon: Building2,
        requiredRoles: ["user:manage"],
      },
    ],
  },
]
