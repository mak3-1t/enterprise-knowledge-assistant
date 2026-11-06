#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'verify-document-scan.ps1') -LogPath 'target/outbox-kafka-verify.log'
