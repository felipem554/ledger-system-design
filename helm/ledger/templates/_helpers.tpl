{{- define "ledger.name" -}}
ledger
{{- end -}}

{{- define "ledger.fullname" -}}
{{ include "ledger.name" . }}-{{ .Release.Name }}
{{- end -}}
