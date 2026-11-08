{{/* 公共标签 */}}
{{- define "meridian-sandbox.labels" -}}
app.kubernetes.io/part-of: agent-meridian
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/* 沙箱 Pod 的选择标签：NetworkPolicy 与 router 的出网规则按它选择 */}}
{{- define "meridian-sandbox.sandboxSelector" -}}
app.kubernetes.io/name: meridian-sandbox
app.kubernetes.io/component: sandbox
{{- end -}}

{{- define "meridian-sandbox.routerSelector" -}}
app.kubernetes.io/name: sandbox-router
app.kubernetes.io/component: sandbox-router
{{- end -}}
