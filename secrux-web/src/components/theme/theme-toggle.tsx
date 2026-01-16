import { Moon, Sun, SunMoon } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { useTheme, type Theme } from "./theme-provider"
import { useTranslation } from "react-i18next"

const options: { value: Theme; icon: React.ReactNode; labelKey: string }[] = [
  { value: "light", icon: <Sun className="mr-2 h-4 w-4" />, labelKey: "theme.light" },
  { value: "dark", icon: <Moon className="mr-2 h-4 w-4" />, labelKey: "theme.dark" },
  { value: "system", icon: <SunMoon className="mr-2 h-4 w-4" />, labelKey: "theme.system" },
]

export function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  const { t } = useTranslation()

  const activeIcon =
    theme === "light" ? <Sun className="h-4 w-4" /> : theme === "dark" ? <Moon className="h-4 w-4" /> : <SunMoon className="h-4 w-4" />

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={t("theme.label")}>
          {activeIcon}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-40">
        {options.map((option) => (
          <DropdownMenuItem key={option.value} onClick={() => setTheme(option.value)}>
            {option.icon}
            {t(option.labelKey)}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

