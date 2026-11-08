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

{{/* 出网代理 Pod 的选择标签：沙箱的出网规则与代理自己的网络策略按它选择 */}}
{{- define "meridian-sandbox.egressProxySelector" -}}
app.kubernetes.io/name: meridian-egress-proxy
app.kubernetes.io/component: egress-proxy
{{- end -}}

{{/* 访问集群 DNS 的出网规则（UDP 与 TCP 53） */}}
{{- define "meridian-sandbox.dnsEgress" -}}
- to:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: {{ .Values.dns.namespace }}
      podSelector:
        matchLabels:
          {{- toYaml .Values.dns.podLabels | nindent 10 }}
  ports:
    - protocol: UDP
      port: 53
    - protocol: TCP
      port: 53
{{- end -}}
