{{/*
차트 기본 이름을 반환한다.
*/}}
{{- define "hangat.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}


{{/*
Release 이름을 포함한 리소스 기본 이름을 반환한다.
release가 hangat이면 최종 이름도 hangat이다.
*/}}
{{- define "hangat.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}


{{/*
모든 리소스에 적용할 공통 Label이다.
*/}}
{{- define "hangat.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "hangat.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: hangat
{{- end }}


{{/*
Deployment와 Service가 공유하는 selector Label이다.
*/}}
{{- define "hangat.selectorLabels" -}}
app.kubernetes.io/name: {{ include "hangat.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}


{{/*
후기 이미지 PVC 이름을 반환한다.
*/}}
{{- define "hangat.backendPvcName" -}}
{{- if .Values.backend.persistence.existingClaim }}
{{- .Values.backend.persistence.existingClaim }}
{{- else }}
{{- printf "%s-backend-uploads" (include "hangat.fullname" .) }}
{{- end }}
{{- end }}