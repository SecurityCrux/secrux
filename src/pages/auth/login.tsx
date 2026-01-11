import { useState } from "react"
import { useForm } from "react-hook-form"
import { z } from "zod"
import { zodResolver } from "@hookform/resolvers/zod"
import { useLocation, useNavigate } from "react-router-dom"
import { useTranslation } from "react-i18next"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { useAuth } from "@/hooks/use-auth"

const loginSchema = z.object({
  username: z.string().min(1, "auth.errors.invalid"),
  password: z.string().min(1, "auth.errors.invalid"),
})

type LoginFormValues = z.infer<typeof loginSchema>

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const { t } = useTranslation()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      username: "",
      password: "",
    },
  })

  const onSubmit = async (values: LoginFormValues) => {
    setFormError(null)
    try {
      await login(values)
      const redirectTo = (location.state as { from?: Location })?.from?.pathname ?? "/"
      navigate(redirectTo, { replace: true })
    } catch {
      setFormError("auth.errors.invalid")
    }
  }

  return (
    <Card className="shadow-lg">
      <CardHeader>
        <CardTitle className="text-center text-2xl font-semibold">{t("auth.title")}</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleSubmit(onSubmit)}>
          <div className="space-y-2">
            <Label htmlFor="username">{t("auth.username")}</Label>
            <Input id="username" autoComplete="username" {...register("username")} />
            {errors.username ? (
              <p className="text-sm text-destructive">{t(errors.username.message ?? "auth.errors.invalid")}</p>
            ) : null}
          </div>
          <div className="space-y-2">
            <Label htmlFor="password">{t("auth.password")}</Label>
            <Input id="password" type="password" autoComplete="current-password" {...register("password")} />
            {errors.password ? (
              <p className="text-sm text-destructive">{t(errors.password.message ?? "auth.errors.invalid")}</p>
            ) : null}
          </div>
          {formError ? (
            <div className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {t(formError)}
            </div>
          ) : null}
          <Button className="w-full" type="submit" disabled={isSubmitting}>
            {isSubmitting ? t("auth.actions.signingIn") : t("auth.actions.signin")}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}

