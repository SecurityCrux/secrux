import { Moon, Sun } from "lucide-react"
import { useMemo, useState } from "react"
import { useTranslation } from "react-i18next"

import { useTheme } from "@/components/theme/theme-provider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { storage } from "@/lib/storage"

type SetupLanguage = "en" | "zh"
type SetupTheme = "light" | "dark"

function resolveTheme(current: string): SetupTheme {
  if (current === "dark" || current === "light") {
    return current
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"
}

export function FirstRunSetup() {
  const { i18n, t } = useTranslation()
  const { theme, setTheme } = useTheme()

  const initialLanguage: SetupLanguage = useMemo(
    () => (i18n.language?.startsWith("zh") ? "zh" : "en"),
    [i18n.language]
  )
  const initialTheme: SetupTheme = useMemo(() => resolveTheme(theme), [theme])

  const [open, setOpen] = useState(() => !storage.isUiSetupDone())
  const [language, setLanguage] = useState<SetupLanguage>(initialLanguage)
  const [themeChoice, setThemeChoice] = useState<SetupTheme>(initialTheme)

  const logoSrc = `${import.meta.env.BASE_URL}logo-m.svg`

  if (!open) {
    return null
  }

  const apply = async () => {
    setTheme(themeChoice)
    await i18n.changeLanguage(language)
    storage.markUiSetupDone()
    setOpen(false)
  }

  return (
    <div className="fixed inset-0 z-50 flex min-h-screen items-center justify-center bg-background/80 p-4 backdrop-blur-sm">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex items-center gap-3">
            <img src={logoSrc} alt="Secrux" className="h-9 w-9" />
            <div>
              <CardTitle>{t("setup.title")}</CardTitle>
              <CardDescription>{t("setup.subtitle")}</CardDescription>
            </div>
          </div>
        </CardHeader>

        <CardContent className="space-y-6">
          <div className="space-y-2">
            <Label htmlFor="setup-language">{t("language.label")}</Label>
            <Select value={language} onValueChange={(value) => setLanguage(value as SetupLanguage)}>
              <SelectTrigger id="setup-language">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="en">English</SelectItem>
                <SelectItem value="zh">简体中文</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label>{t("theme.label")}</Label>
            <div className="grid grid-cols-2 gap-2">
              <Button
                type="button"
                variant={themeChoice === "light" ? "default" : "outline"}
                className="justify-start"
                onClick={() => setThemeChoice("light")}
              >
                <Sun className="mr-2 h-4 w-4" />
                {t("theme.light")}
              </Button>
              <Button
                type="button"
                variant={themeChoice === "dark" ? "default" : "outline"}
                className="justify-start"
                onClick={() => setThemeChoice("dark")}
              >
                <Moon className="mr-2 h-4 w-4" />
                {t("theme.dark")}
              </Button>
            </div>
          </div>
        </CardContent>

        <CardFooter className="justify-end">
          <Button type="button" onClick={apply}>
            {t("setup.continue")}
          </Button>
        </CardFooter>
      </Card>
    </div>
  )
}

