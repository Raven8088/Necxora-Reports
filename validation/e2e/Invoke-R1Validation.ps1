#Requires -Version 7.0
<#
.SYNOPSIS
  End-to-end validation of Nexora Reports R1 with real Keycloak, real Nexora Semantic, real Nexora Discovery,
  PostgreSQL and SYNTHETIC data only. Validation tooling: it adds no functionality to Reports.

.DESCRIPTION
  1. Discovery reads the synthetic database; Semantic imports it in a NEW data source of tenant "demo" (and another
     one of tenant "other"); definitions and relationships are created by e2e.editor and approved by e2e.approver.
  2. The REPORTS_CLIENT service account (nexora-reports-dev) creates six logical plans in Semantic.
  3. Reports R1 is started (optional) and every check is executed against its HTTP API.
  4. A Markdown + JSON report is written to validation/out. Secrets and tokens are never written.

  Keycloak: with -ApplyKeycloakSetup the script ADDS to the realm (idempotent) the role REPORT_RUNNER, the public
  client nexora-reports-e2e (audiences nexora-semantic + nexora-reports, principal_type HUMAN), the public client
  nexora-reports-e2e-ai (principal_type AI), the confidential client nexora-reports-e2e-other (service account,
  REPORTS_CLIENT, group tenant-other) and the users e2e.editor, e2e.approver, e2e.runner (tenant-demo) and
  e2e.runner.other (tenant-other) with random passwords. Nothing existing is modified. -RevertKeycloakSetup deletes
  exactly those objects. Without either switch Keycloak is only read.

  Semantic data: the run creates new data sources, definitions, relationships and plans (synthetic) in the tenants
  demo and other, and deactivates ONE relationship it created itself (to produce a STALE plan).
#>
[CmdletBinding()]
param(
    [string]$KeycloakUrl = 'http://localhost:8180',
    [string]$Realm = 'nexora',
    [string]$SemanticUrl = 'http://localhost:8080',
    [string]$DiscoveryUrl = 'http://localhost:8070',
    [string]$ReportsUrl = 'http://localhost:8090',
    [string]$SyntheticHost = '127.0.0.1',
    [int]$SyntheticPort = 5434,
    [string]$SyntheticDatabase = 'synthetic_erp',
    [string]$ReportsDatabaseUrl = 'jdbc:postgresql://localhost:5434/nexora_reports?currentSchema=reports',
    # .env files with the development secrets (read locally, never printed).
    [string]$SemanticEnvFile = (Join-Path $PSScriptRoot '..\..\..\Nexora-Semantic\docker\.env'),
    [string]$ReportsEnvFile = (Join-Path $PSScriptRoot '..\..\docker\.env'),
    # Command that runs psql as a superuser of the PostgreSQL holding synthetic_erp and nexora_reports.
    [string[]]$Psql = @('docker', 'exec', 'nexora-reports-postgres', 'psql', '-U', 'nexora_reports_owner'),
    [string]$ReportsJar = (Join-Path $PSScriptRoot '..\..\target\nexora-reports-0.1.0-SNAPSHOT.jar'),
    [switch]$StartReports,
    [switch]$ApplyKeycloakSetup,
    [switch]$RevertKeycloakSetup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# ---------------------------------------------------------------- helpers

function Read-DotEnv([string]$Path) {
    $values = @{}
    if (Test-Path $Path) {
        foreach ($line in Get-Content $Path) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
                $values[$Matches[1]] = $Matches[2].Trim('"').Trim("'")
            }
        }
    }
    return $values
}

function Get-Secret([hashtable]$File, [string]$Name) {
    $fromEnv = [Environment]::GetEnvironmentVariable($Name)
    if ($fromEnv) { return $fromEnv }
    if ($File.ContainsKey($Name) -and $File[$Name]) { return $File[$Name] }
    throw "Secret $Name not found (environment variable or .env file)."
}

function New-Password { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ }) }

function Invoke-Http {
    param([string]$Method = 'GET', [string]$Url, [string]$Token, $Body, [string]$RawBody,
          [hashtable]$Headers = @{}, [string]$ContentType = 'application/json', [hashtable]$Form,
          [switch]$NoBody)
    $h = @{} + $Headers
    if ($Token) { $h['Authorization'] = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = $Url; Headers = $h; SkipHttpErrorCheck = $true; TimeoutSec = 120 }
    if ($Form) {
        $params['Body'] = $Form
        $params['ContentType'] = 'application/x-www-form-urlencoded'
    } elseif ($PSBoundParameters.ContainsKey('RawBody')) {
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes($RawBody)
        $params['ContentType'] = $ContentType
    } elseif ($null -ne $Body) {
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Body -Depth 50 -Compress))
        $params['ContentType'] = $ContentType
    }
    if ($NoBody) {
        # Invoke-WebRequest adds "application/x-www-form-urlencoded" to a body-less POST; use HttpClient directly.
        $client = [System.Net.Http.HttpClient]::new()
        try {
            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Url)
            foreach ($k in $h.Keys) { [void]$request.Headers.TryAddWithoutValidation($k, $h[$k]) }
            $resp = $client.Send($request)
            $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $json = $null
            if ($text -and ($text.TrimStart().StartsWith('{') -or $text.TrimStart().StartsWith('['))) {
                try { $json = $text | ConvertFrom-Json -Depth 100 -AsHashtable -NoEnumerate } catch { $json = $null }
            }
            $hdrs = @{}
            foreach ($x in $resp.Headers) { $hdrs[$x.Key] = ($x.Value -join ',') }
            return [pscustomobject]@{ Status = [int]$resp.StatusCode; Text = $text; Json = $json; Headers = $hdrs }
        } finally { $client.Dispose() }
    }
    $r = Invoke-WebRequest @params
    $text = if ($r.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
    $json = $null
    if ($text -and $text.TrimStart().StartsWith('{') -or ($text -and $text.TrimStart().StartsWith('['))) {
        try { $json = $text | ConvertFrom-Json -Depth 100 -AsHashtable -NoEnumerate } catch { $json = $null }
    }
    return [pscustomobject]@{ Status = [int]$r.StatusCode; Text = $text; Json = $json; Headers = $r.Headers }
}

function Expect([object]$Response, [int[]]$Status, [string]$What) {
    if ($Status -notcontains $Response.Status) {
        $snippet = if ($Response.Text) { $Response.Text.Substring(0, [Math]::Min(400, $Response.Text.Length)) } else { '' }
        throw "$What -> HTTP $($Response.Status) (expected $($Status -join '/')): $snippet"
    }
    return $Response
}

function ConvertFrom-Jwt([string]$Token) {
    $p = $Token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json -AsHashtable
}

function Get-PasswordToken([string]$Client, [string]$User, [string]$Password) {
    $r = Invoke-Http -Method POST -Url "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" -Form @{
        grant_type = 'password'; client_id = $Client; username = $User; password = $Password }
    return (Expect $r 200 "token for $User via $Client").Json['access_token']
}

function Get-ClientToken([string]$Client, [string]$Secret) {
    $r = Invoke-Http -Method POST -Url "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" -Form @{
        grant_type = 'client_credentials'; client_id = $Client; client_secret = $Secret }
    return (Expect $r 200 "service token for $Client").Json['access_token']
}

function Invoke-Sql([string]$Database, [string]$Sql) {
    $cmd = $Psql + @('-d', $Database, '-v', 'ON_ERROR_STOP=1', '-tA', '-c', $Sql)
    $out = & $cmd[0] $cmd[1..($cmd.Length - 1)] 2>&1
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = (($out | ForEach-Object { "$_" }) -join "`n").Trim() }
}

function Rows([object]$Response) {
    return ConvertTo-Json -InputObject $Response.Json['rows'] -Compress -Depth 10
}

$checks = [System.Collections.Generic.List[object]]::new()
function Check([string]$CheckId, [string]$CheckTitle, [scriptblock]$CheckBody) {
    try {
        $detail = & $CheckBody
        $checks.Add([pscustomobject]@{ Id = $CheckId; Check = $CheckTitle; Result = 'PASS'; Detail = "$detail" })
        Write-Host "[PASS] $CheckId $CheckTitle" -ForegroundColor Green
    } catch {
        $checks.Add([pscustomobject]@{ Id = $CheckId; Check = $CheckTitle; Result = 'FAIL'; Detail = $_.Exception.Message })
        Write-Host "[FAIL] $CheckId $CheckTitle :: $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }

function Invoke-Execution([string]$Token, [string]$DataSource, [string]$Plan, [string]$Body) {
    $url = "$ReportsUrl/api/v1/data-sources/$DataSource/plans/$Plan/executions"
    if ($PSBoundParameters.ContainsKey('Body')) {
        return Invoke-Http -Method POST -Url $url -Token $Token -RawBody $Body
    }
    return Invoke-Http -Method POST -Url $url -Token $Token -NoBody
}

# ---------------------------------------------------------------- configuration

$semanticEnv = Read-DotEnv $SemanticEnvFile
$reportsEnv = Read-DotEnv $ReportsEnvFile
$devPassword = Get-Secret $semanticEnv 'KC_DEV_USER_PASSWORD'
$reportsClientSecret = Get-Secret $semanticEnv 'KC_REPORTS_CLIENT_SECRET'
$readerPassword = Get-Secret $reportsEnv 'SYNTHETIC_READER_PASSWORD'
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
$runStart = (Get-Date).ToUniversalTime().AddSeconds(-5).ToString('yyyy-MM-ddTHH:mm:ssZ')
$outDir = Join-Path $PSScriptRoot '..\out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$E2E_USERS = @(
    @{ Name = 'e2e.editor'; Role = 'EDITOR'; Group = 'tenant-demo' },
    @{ Name = 'e2e.approver'; Role = 'APPROVER'; Group = 'tenant-demo' },
    @{ Name = 'e2e.runner'; Role = 'REPORT_RUNNER'; Group = 'tenant-demo' },
    @{ Name = 'e2e.runner.other'; Role = 'REPORT_RUNNER'; Group = 'tenant-other' })
$E2E_CLIENT = 'nexora-reports-e2e'
$E2E_AI_CLIENT = 'nexora-reports-e2e-ai'
$E2E_OTHER_SERVICE = 'nexora-reports-e2e-other'

# ---------------------------------------------------------------- Keycloak (admin API, only with switches)

function Get-AdminToken {
    $adminPassword = Get-Secret $semanticEnv 'KC_BOOTSTRAP_ADMIN_PASSWORD'
    $r = Invoke-Http -Method POST -Url "$KeycloakUrl/realms/master/protocol/openid-connect/token" -Form @{
        grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = $adminPassword }
    return (Expect $r 200 'Keycloak admin token').Json['access_token']
}

function Get-KcClient([string]$Admin, [string]$ClientId) {
    $r = Expect (Invoke-Http -Url "$KeycloakUrl/admin/realms/$Realm/clients?clientId=$ClientId" -Token $Admin) 200 'get client'
    if ($r.Json.Count -gt 0) { return $r.Json[0] } else { return $null }
}

function Get-KcGroupId([string]$Admin, [string]$Name) {
    $r = Expect (Invoke-Http -Url "$KeycloakUrl/admin/realms/$Realm/groups?search=$Name&exact=true" -Token $Admin) 200 'get group'
    $g = $r.Json | Where-Object { $_['name'] -eq $Name } | Select-Object -First 1
    if (-not $g) { throw "Group $Name not found in realm $Realm" }
    return $g['id']
}

function Get-KcRole([string]$Admin, [string]$Name) {
    return (Expect (Invoke-Http -Url "$KeycloakUrl/admin/realms/$Realm/roles/$Name" -Token $Admin) 200 "role $Name").Json
}

function Ensure-KcClient([string]$Admin, [hashtable]$Representation) {
    $existing = Get-KcClient $Admin $Representation['clientId']
    if (-not $existing) {
        Expect (Invoke-Http -Method POST -Url "$KeycloakUrl/admin/realms/$Realm/clients" -Token $Admin -Body $Representation) 201 "create client $($Representation['clientId'])" | Out-Null
        $existing = Get-KcClient $Admin $Representation['clientId']
    }
    $scopes = Expect (Invoke-Http -Url "$KeycloakUrl/admin/realms/$Realm/client-scopes" -Token $Admin) 200 'client scopes'
    $scope = $scopes.Json | Where-Object { $_['name'] -eq 'nexora-semantic' } | Select-Object -First 1
    if (-not $scope) { throw "client scope nexora-semantic not found" }
    Expect (Invoke-Http -Method PUT -Url "$KeycloakUrl/admin/realms/$Realm/clients/$($existing['id'])/default-client-scopes/$($scope['id'])" -Token $Admin) 204 'default scope' | Out-Null
    return $existing
}

function Mapper([string]$Name, [string]$Type, [hashtable]$Config) {
    return @{ name = $Name; protocol = 'openid-connect'; protocolMapper = $Type; config = $Config }
}

function Invoke-KeycloakSetup {
    $admin = Get-AdminToken
    $role = Invoke-Http -Method POST -Url "$KeycloakUrl/admin/realms/$Realm/roles" -Token $admin -Body @{
        name = 'REPORT_RUNNER'; description = 'Nexora Reports: execute authorized plans (e2e validation)' }
    Expect $role @(201, 409) 'create role REPORT_RUNNER' | Out-Null

    $audience = Mapper 'reports-audience' 'oidc-audience-mapper' @{
        'included.custom.audience' = 'nexora-reports'; 'access.token.claim' = 'true'; 'id.token.claim' = 'false' }
    Ensure-KcClient $admin @{ clientId = $E2E_CLIENT; name = 'Nexora Reports E2E (validation)'; publicClient = $true
        directAccessGrantsEnabled = $true; standardFlowEnabled = $false; serviceAccountsEnabled = $false
        protocolMappers = @($audience, (Mapper 'principal-type' 'oidc-hardcoded-claim-mapper' @{
            'claim.name' = 'principal_type'; 'claim.value' = 'HUMAN'; 'jsonType.label' = 'String'
            'access.token.claim' = 'true' })) } | Out-Null
    Ensure-KcClient $admin @{ clientId = $E2E_AI_CLIENT; name = 'Nexora Reports E2E AI principal (validation)'
        publicClient = $true; directAccessGrantsEnabled = $true; standardFlowEnabled = $false
        protocolMappers = @($audience, (Mapper 'principal-type' 'oidc-hardcoded-claim-mapper' @{
            'claim.name' = 'principal_type'; 'claim.value' = 'AI'; 'jsonType.label' = 'String'
            'access.token.claim' = 'true' })) } | Out-Null
    $other = Ensure-KcClient $admin @{ clientId = $E2E_OTHER_SERVICE; name = 'Reports service account, tenant other (validation)'
        publicClient = $false; serviceAccountsEnabled = $true; directAccessGrantsEnabled = $false
        standardFlowEnabled = $false
        protocolMappers = @((Mapper 'principal-type' 'oidc-hardcoded-claim-mapper' @{
            'claim.name' = 'principal_type'; 'claim.value' = 'SERVICE'; 'jsonType.label' = 'String'
            'access.token.claim' = 'true' })) }
    $secret = (Expect (Invoke-Http -Method POST -Url "$KeycloakUrl/admin/realms/$Realm/clients/$($other['id'])/client-secret" -Token $admin) 200 'client secret').Json['value']
    $svc = (Expect (Invoke-Http -Url "$KeycloakUrl/admin/realms/$Realm/clients/$($other['id'])/service-account-user" -Token $admin) 200 'service account user').Json
    Expect (Invoke-Http -Method POST -Url "$KeycloakUrl/admin/realms/$Realm/users/$($svc['id'])/role-mappings/realm" -Token $admin -Body @(Get-KcRole $admin 'REPORTS_CLIENT')) 204 'REPORTS_CLIENT' | Out-Null
    Expect (Invoke-Http -Method PUT -Url "$KeycloakUrl/admin/realms/$Realm/users/$($svc['id'])/groups/$(Get-KcGroupId $admin 'tenant-other')" -Token $admin) 204 'group tenant-other' | Out-Null

    $passwords = @{}
    foreach ($u in $E2E_USERS) {
        $found = (Expect (Invoke-Http -Url "$KeycloakUrl/admin/realms/$Realm/users?username=$($u.Name)&exact=true" -Token $admin) 200 'find user').Json
        if ($found.Count -eq 0) {
            Expect (Invoke-Http -Method POST -Url "$KeycloakUrl/admin/realms/$Realm/users" -Token $admin -Body @{
                username = $u.Name; enabled = $true; emailVerified = $true; firstName = 'E2E'; lastName = $u.Name
                email = "$($u.Name)@e2e.invalid"; requiredActions = @() }) 201 "create $($u.Name)" | Out-Null
            $found = (Expect (Invoke-Http -Url "$KeycloakUrl/admin/realms/$Realm/users?username=$($u.Name)&exact=true" -Token $admin) 200 'find user').Json
        }
        $id = $found[0]['id']
        $password = New-Password
        Expect (Invoke-Http -Method PUT -Url "$KeycloakUrl/admin/realms/$Realm/users/$id/reset-password" -Token $admin -Body @{
            type = 'password'; value = $password; temporary = $false }) 204 'password' | Out-Null
        Expect (Invoke-Http -Method POST -Url "$KeycloakUrl/admin/realms/$Realm/users/$id/role-mappings/realm" -Token $admin -Body @(Get-KcRole $admin $u.Role)) 204 'role' | Out-Null
        Expect (Invoke-Http -Method PUT -Url "$KeycloakUrl/admin/realms/$Realm/users/$id/groups/$(Get-KcGroupId $admin $u.Group)" -Token $admin) 204 'group' | Out-Null
        $passwords[$u.Name] = $password
    }
    return @{ Passwords = $passwords; OtherSecret = $secret }
}

function Invoke-KeycloakRevert {
    $admin = Get-AdminToken
    foreach ($u in $E2E_USERS) {
        $found = (Invoke-Http -Url "$KeycloakUrl/admin/realms/$Realm/users?username=$($u.Name)&exact=true" -Token $admin).Json
        if ($found -and $found.Count -gt 0) {
            Invoke-Http -Method DELETE -Url "$KeycloakUrl/admin/realms/$Realm/users/$($found[0]['id'])" -Token $admin | Out-Null
        }
    }
    foreach ($c in @($E2E_CLIENT, $E2E_AI_CLIENT, $E2E_OTHER_SERVICE)) {
        $client = Get-KcClient $admin $c
        if ($client) { Invoke-Http -Method DELETE -Url "$KeycloakUrl/admin/realms/$Realm/clients/$($client['id'])" -Token $admin | Out-Null }
    }
    Invoke-Http -Method DELETE -Url "$KeycloakUrl/admin/realms/$Realm/roles/REPORT_RUNNER" -Token $admin | Out-Null
    Write-Host 'Keycloak validation objects removed.'
}

if ($RevertKeycloakSetup) { Invoke-KeycloakRevert; return }
if (-not $ApplyKeycloakSetup) {
    throw 'The validation needs its Keycloak users and clients. Re-run with -ApplyKeycloakSetup (after authorization).'
}

# ---------------------------------------------------------------- preflight

Write-Host "Run $runId" -ForegroundColor Cyan
Expect (Invoke-Http -Url "$KeycloakUrl/realms/$Realm/.well-known/openid-configuration") 200 'Keycloak realm' | Out-Null
Expect (Invoke-Http -Url "$SemanticUrl/actuator/health") 200 'Semantic health' | Out-Null
$kc = Invoke-KeycloakSetup
$pw = $kc.Passwords

$steward = Get-PasswordToken 'nexora-semantic-dev' 'steward.demo' $devPassword
$stewardOther = Get-PasswordToken 'nexora-semantic-dev' 'steward.other' $devPassword
$editor = Get-PasswordToken 'nexora-semantic-dev' 'e2e.editor' $pw['e2e.editor']
$approver = Get-PasswordToken 'nexora-semantic-dev' 'e2e.approver' $pw['e2e.approver']
$service = Get-ClientToken 'nexora-reports-dev' $reportsClientSecret
$demoTenant = (ConvertFrom-Jwt $steward)['tenant']
$otherTenant = (ConvertFrom-Jwt $stewardOther)['tenant']
if (-not $demoTenant -or $demoTenant -eq '00000000-0000-0000-0000-000000000000') {
    throw 'Tenants are not configured in Keycloak (see Nexora-Semantic README, "Alta de tenants").'
}
Assert ((ConvertFrom-Jwt $service)['tenant'] -eq $demoTenant) 'nexora-reports-dev must belong to tenant demo'

# ---------------------------------------------------------------- synthetic catalog in Semantic

function New-SemanticSource([string]$Token, [string]$Key) {
    $ds = Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources" -Token $Token -Body @{
        key = $Key; name = "R1 validation $runId"; engine = 'postgresql'; databaseName = $SyntheticDatabase }) 201 'create data source'
    return $ds.Json['id']
}

$discovery = Expect (Invoke-Http -Method POST -Url "$DiscoveryUrl/api/discovery" -Body @{
    engine = 'postgresql'
    connection = @{ host = $SyntheticHost; port = $SyntheticPort; database = $SyntheticDatabase
        username = 'nexora_reader'; password = $readerPassword; sslMode = 'disable' }
    schemas = @('plant', 'finance', 'docs') }) 200 'Discovery of the synthetic database'
$envelope = '{"contractVersion":"discovery-api/0.1","discoveredAt":"' + (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') +
    '","requestedSchemas":["plant","finance","docs"],"payload":' + $discovery.Text + '}'

$ds = New-SemanticSource $steward "r1-e2e-$runId"
Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$ds/snapshots" -Token $steward -RawBody $envelope) 201 'import snapshot (demo)' | Out-Null
$dsOther = New-SemanticSource $stewardOther "r1-e2e-other-$runId"
Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$dsOther/snapshots" -Token $stewardOther -RawBody $envelope) 201 'import snapshot (other)' | Out-Null

$objects = @{}
foreach ($item in (Expect (Invoke-Http -Url "$SemanticUrl/api/v1/data-sources/$ds/objects?size=200" -Token $editor) 200 'objects').Json['items']) {
    $objects[$item['naturalKey']] = $item['id']
}
function Obj([string]$Schema, [string]$Table, [string]$Column) {
    $key = "`"$Schema`".`"$Table`"" + $(if ($Column) { ".`"$Column`"" } else { '' })
    if (-not $objects.ContainsKey($key)) { throw "object $key not imported" }
    return $objects[$key]
}

function Approved([hashtable]$Definition) {
    $created = Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$ds/definitions" -Token $editor -Body $Definition) 201 "definition $($Definition['key'])"
    $id = $created.Json['id']
    Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$ds/definitions/$id/approve" -Token $approver -Headers @{ 'If-Match' = '"0"' }) 200 "approve $($Definition['key'])" | Out-Null
    return $id
}
function Concept([string]$Key, [string]$Schema, [string]$Table) {
    Approved @{ kind = 'CONCEPT'; key = $Key; name = $Key; concept = @{ relationId = (Obj $Schema $Table) } }
}
function Metric([string]$Key, [string]$Concept, [string]$Aggregation, [string]$Column) {
    $block = @{ conceptId = $Concept; aggregation = $Aggregation }
    if ($Column) { $block['columnId'] = $Column }
    Approved @{ kind = 'METRIC'; key = $Key; name = $Key; metric = $block }
}
function Dimension([string]$Key, [string]$Concept, [string]$Column, [string]$Type) {
    Approved @{ kind = 'DIMENSION'; key = $Key; name = $Key; dimension = @{ conceptId = $Concept; columnId = $Column; type = $Type } }
}
function Relationship([string]$Source, [string]$Target, [string[]]$Joins, [object[]]$Pairs) {
    $created = Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$ds/relationships" -Token $editor -Body @{
        name = "$Source -> $Target"; sourceObjectId = $Source; targetObjectId = $Target; pairs = $Pairs
        declaredCardinality = 'MANY_TO_ONE'; allowedJoinTypes = $Joins }) 201 'relationship'
    $id = $created.Json['id']
    Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$ds/relationships/$id/approve" -Token $approver -Headers @{ 'If-Match' = '"0"' }) 200 'approve relationship' | Out-Null
    return $id
}

$lecturas = Concept 'lecturas' 'plant' 'measurements'
$equipos = Concept 'equipos' 'plant' 'equipment'
$sensores = Concept 'sensores' 'plant' 'sensors'
$movimientos = Concept 'movimientos' 'finance' 'transactions'
$documentos = Concept 'documentos' 'docs' 'documents'
$sumaValor = Metric 'suma_valor' $lecturas 'SUM' (Obj 'plant' 'measurements' 'value')
$numLecturas = Metric 'num_lecturas' $lecturas 'COUNT' $null
$numSensores = Metric 'num_sensores' $sensores 'COUNT' $null
$importe = Metric 'importe' $movimientos 'SUM' (Obj 'finance' 'transactions' 'amount')
$fecha = Dimension 'fecha' $lecturas (Obj 'plant' 'measurements' 'measured_at') 'TEMPORAL'
$nota = Dimension 'nota' $lecturas (Obj 'plant' 'measurements' 'note') 'CATEGORICAL'
$nombreEquipo = Dimension 'nombre_equipo' $equipos (Obj 'plant' 'equipment' 'name') 'CATEGORICAL'
$titulo = Dimension 'titulo' $documentos (Obj 'docs' 'documents' 'title') 'CATEGORICAL'
Relationship (Obj 'finance' 'transactions') (Obj 'docs' 'documents') @('INNER') @(
    @{ sourceColumnId = (Obj 'finance' 'transactions' 'company_code'); targetColumnId = (Obj 'docs' 'documents' 'company_code') },
    @{ sourceColumnId = (Obj 'finance' 'transactions' 'document_number'); targetColumnId = (Obj 'docs' 'documents' 'number') }) | Out-Null
$sensorsRel = Relationship (Obj 'plant' 'sensors') (Obj 'plant' 'equipment') @('INNER', 'LEFT') @(
    @{ sourceColumnId = (Obj 'plant' 'sensors' 'equipment_id'); targetColumnId = (Obj 'plant' 'equipment' 'id') })
$measurementsRel = Relationship (Obj 'plant' 'measurements') (Obj 'plant' 'equipment') @('INNER', 'LEFT') @(
    @{ sourceColumnId = (Obj 'plant' 'measurements' 'equipment_dbid'); targetColumnId = (Obj 'plant' 'equipment' 'id') })

function Plan([hashtable]$Request) {
    foreach ($k in 'dimensions', 'filters', 'preferredRelationships', 'timeGranularities') {
        if (-not $Request.ContainsKey($k)) { $Request[$k] = @() }
    }
    $r = Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$ds/logical-plans" -Token $service -Body $Request) 201 'create logical plan'
    return $r.Json['id']
}
$plans = [ordered]@{
    P1 = Plan @{ metrics = @($sumaValor, $numLecturas); dimensions = @($fecha, $nota)
        filters = @(@{ definitionId = $nota; operator = 'IN'; values = @('ok', 'revisar') },
                    @{ definitionId = $fecha; operator = 'GTE'; value = '2026-01-01T00:00:00+01:00' },
                    @{ definitionId = $sumaValor; operator = 'GT'; value = 100 })
        timeGranularities = @(@{ dimensionId = $fecha; granularity = 'MONTH' }) }
    P2 = Plan @{ metrics = @($importe); dimensions = @($titulo) }
    P3 = Plan @{ metrics = @($numSensores); dimensions = @($nombreEquipo) }
    P4 = Plan @{ metrics = @($sumaValor); dimensions = @($nombreEquipo); preferredRelationships = @($measurementsRel)
        filters = @(@{ definitionId = $nota; operator = 'NE'; value = 'descartada' },
                    @{ definitionId = $sumaValor; operator = 'LTE'; value = 1000 }) }
    P5 = Plan @{ metrics = @($numLecturas); dimensions = @($fecha); timeGranularities = @(@{ dimensionId = $fecha; granularity = 'WEEK' }) }
    P6 = Plan @{ metrics = @($numLecturas); dimensions = @($nota); filters = @(@{ definitionId = $nota; operator = 'IS_NOT_NULL' }) }
}
$expected = @{
    P1 = '[["2026-01-01","revisar","200.500",1]]'
    P2 = '[["Factura A","150.50"],["Factura B","20.00"],["Factura C","7.00"]]'
    P3 = '[["Bomba",2],["Compresor",1]]'
    P4 = '[["Bomba","126.250"],["Compresor","200.500"]]'
    P5 = '[["2025-11-17",1],["2025-12-29",1],["2026-01-05",1],["2026-01-12",2],["2026-01-26",1],["2026-02-02",1],["2026-02-09",1]]'
    P6 = '[["descartada",1],["ok",4],["revisar",2]]'
}

# ---------------------------------------------------------------- Reports

$reportsProcess = $null
if ($StartReports) {
    $env:REPORTS_DB_URL = $ReportsDatabaseUrl
    $env:REPORTS_DB_USER = 'nexora_reports_app'
    $env:REPORTS_DB_PASSWORD = Get-Secret $reportsEnv 'REPORTS_DB_APP_PASSWORD'
    $env:REPORTS_DB_OWNER_USER = $(if ($reportsEnv['REPORTS_DB_OWNER_USER']) { $reportsEnv['REPORTS_DB_OWNER_USER'] } else { 'nexora_reports_owner' })
    $env:REPORTS_DB_OWNER_PASSWORD = Get-Secret $reportsEnv 'REPORTS_DB_OWNER_PASSWORD'
    $env:REPORTS_OIDC_ISSUER_URI = "$KeycloakUrl/realms/$Realm"
    $env:REPORTS_SEMANTIC_BASE_URL = $SemanticUrl
    $env:REPORTS_SEMANTIC_TOKEN_URI = "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token"
    $env:REPORTS_PORT = ([Uri]$ReportsUrl).Port
    $env:NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_TENANTID = $demoTenant
    $env:NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_CLIENTID = 'nexora-reports-dev'
    $env:NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_CLIENTSECRET = $reportsClientSecret
    $env:NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_1_TENANTID = $otherTenant
    $env:NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_1_CLIENTID = $E2E_OTHER_SERVICE
    $env:NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_1_CLIENTSECRET = $kc.OtherSecret
    $env:NEXORA_REPORTS_ENTERPRISE_ALLOWEDHOSTS_0 = $SyntheticHost
    $env:NEXORA_REPORTS_ENTERPRISE_ALLOWEDPORTS_0 = "$SyntheticPort"
    $i = 0
    foreach ($pair in @(@($demoTenant, $ds), @($otherTenant, $dsOther))) {
        Set-Item "env:NEXORA_REPORTS_ENTERPRISE_SOURCES_${i}_TENANTID" $pair[0]
        Set-Item "env:NEXORA_REPORTS_ENTERPRISE_SOURCES_${i}_DATASOURCEID" $pair[1]
        Set-Item "env:NEXORA_REPORTS_ENTERPRISE_SOURCES_${i}_HOST" $SyntheticHost
        Set-Item "env:NEXORA_REPORTS_ENTERPRISE_SOURCES_${i}_PORT" "$SyntheticPort"
        Set-Item "env:NEXORA_REPORTS_ENTERPRISE_SOURCES_${i}_DATABASE" $SyntheticDatabase
        Set-Item "env:NEXORA_REPORTS_ENTERPRISE_SOURCES_${i}_USERNAME" 'nexora_reader'
        Set-Item "env:NEXORA_REPORTS_ENTERPRISE_SOURCES_${i}_PASSWORD" $readerPassword
        Set-Item "env:NEXORA_REPORTS_ENTERPRISE_SOURCES_${i}_SSLMODE" 'disable'
        $i++
    }
    $log = Join-Path $outDir "reports-$runId.log"
    $reportsProcess = Start-Process -FilePath 'java' -ArgumentList @('-jar', (Resolve-Path $ReportsJar).Path) `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -NoNewWindow
    $up = $false
    foreach ($n in 1..60) {
        Start-Sleep -Seconds 2
        try { if ((Invoke-Http -Url "$ReportsUrl/actuator/health").Text -match '"UP"') { $up = $true; break } } catch { }
    }
    if (-not $up) { throw "Reports did not start; see $log" }
}

try {
    $runner = Get-PasswordToken $E2E_CLIENT 'e2e.runner' $pw['e2e.runner']
    $runnerOther = Get-PasswordToken $E2E_CLIENT 'e2e.runner.other' $pw['e2e.runner.other']
    $runnerClaims = ConvertFrom-Jwt $runner

    # ------------------------------------------------------------ 1. authentication and tenant from the JWT
    Check '1.1' 'Without a token Reports answers 401' {
        $r = Invoke-Execution $null $ds $plans.P1
        Assert ($r.Status -eq 401 -and $r.Json['code'] -eq 'unauthorized') "HTTP $($r.Status)"
        'HTTP 401 unauthorized'
    }
    Check '1.2' 'A token without the nexora-reports audience is refused (401)' {
        $semanticOnly = Get-PasswordToken 'nexora-semantic-dev' 'e2e.runner' $pw['e2e.runner']
        $aud = (ConvertFrom-Jwt $semanticOnly)['aud']
        $r = Invoke-Execution $semanticOnly $ds $plans.P1
        Assert ($r.Status -eq 401) "HTTP $($r.Status)"
        "aud=$aud -> HTTP 401"
    }
    Check '1.3' 'A user without REPORT_RUNNER is refused (403)' {
        $viewer = Get-PasswordToken $E2E_CLIENT 'viewer.demo' $devPassword
        $r = Invoke-Execution $viewer $ds $plans.P1
        Assert ($r.Status -eq 403 -and $r.Json['code'] -eq 'forbidden') "HTTP $($r.Status)"
        'viewer.demo -> HTTP 403 forbidden'
    }
    Check '1.4' 'An AI principal with REPORT_RUNNER is refused (403)' {
        $ai = Get-PasswordToken $E2E_AI_CLIENT 'e2e.runner' $pw['e2e.runner']
        Assert ((ConvertFrom-Jwt $ai)['principal_type'] -eq 'AI') 'token is not AI'
        $r = Invoke-Execution $ai $ds $plans.P1
        Assert ($r.Status -eq 403) "HTTP $($r.Status)"
        'principal_type=AI -> HTTP 403'
    }
    $p1 = $null
    Check '1.5' 'A real runner is authenticated and the tenant comes from its JWT' {
        $script:p1 = Expect (Invoke-Execution $runner $ds $plans.P1) 200 'execute P1'
        $audit = Invoke-Sql 'nexora_reports' "SELECT tenant_id || '|' || requester_type FROM reports.report_execution_audit WHERE execution_id = '$($script:p1.Json['executionId'])'"
        Assert ($audit.Output -eq "$($runnerClaims['tenant'])|HUMAN") "audit: $($audit.Output)"
        "token tenant=$($runnerClaims['tenant']); audit tenant identical; X-Execution-Id=$($script:p1.Headers['X-Execution-Id'])"
    }

    # ------------------------------------------------------------ 2-3. service account, tenant and source of the plan
    Check '2.1' 'The REPORTS_CLIENT service account reads plans and validity from Semantic' {
        $plan = Expect (Invoke-Http -Url "$SemanticUrl/api/v1/data-sources/$ds/logical-plans/$($plans.P1)" -Token $service) 200 'GET plan'
        $validity = Expect (Invoke-Http -Url "$SemanticUrl/api/v1/data-sources/$ds/logical-plans/$($plans.P1)/validity" -Token $service) 200 'GET validity'
        Assert ($plan.Json['formatVersion'] -eq 'nexora-logical-plan/1') 'format'
        "formatVersion=$($plan.Json['formatVersion']); validity=$($validity.Json['status'])"
    }
    Check '3.1' 'The plan belongs to the authorized tenant and data source' {
        $plan = (Expect (Invoke-Http -Url "$SemanticUrl/api/v1/data-sources/$ds/logical-plans/$($plans.P1)" -Token $service) 200 'GET plan').Json
        Assert ($plan['plan']['tenantId'] -eq $demoTenant -and $plan['plan']['dataSourceId'] -eq $ds -and $plan['dataSourceId'] -eq $ds) 'identity'
        Assert ($script:p1.Json['planFingerprint'] -eq $plan['fingerprint']) 'fingerprint returned by Reports differs'
        "plan.tenantId=$($plan['plan']['tenantId']) plan.dataSourceId=$($plan['plan']['dataSourceId'])"
    }
    Check '3.2' 'Another tenant cannot use this tenant''s data source (409, no credentials for that pair)' {
        $r = Invoke-Execution $runnerOther $ds $plans.P1
        Assert ($r.Status -eq 409 -and $r.Json['code'] -eq 'data_source_not_configured') "HTTP $($r.Status) $($r.Json['code'])"
        'e2e.runner.other on the demo source -> 409 data_source_not_configured'
    }
    Check '3.3' 'Through its own source, the other tenant cannot reach this tenant''s plan (404 from Semantic)' {
        $r = Invoke-Execution $runnerOther $dsOther $plans.P1
        Assert ($r.Status -eq 404 -and $r.Json['code'] -eq 'not_found') "HTTP $($r.Status) $($r.Json['code'])"
        'e2e.runner.other on its own source with the demo plan id -> 404 not_found'
    }
    Check '3.4' 'This tenant cannot use the other tenant''s source' {
        $r = Invoke-Execution $runner $dsOther $plans.P1
        Assert ($r.Status -eq 409) "HTTP $($r.Status) $($r.Json['code'])"
        'e2e.runner on the other source -> 409 data_source_not_configured'
    }

    # ------------------------------------------------------------ 4-5. real plans, expected synthetic results
    foreach ($planKey in @($plans.Keys)) {
        Check "4.$planKey" "Plan $planKey generated by Semantic executes with the expected synthetic result" {
            $r = if ($planKey -eq 'P1') { $script:p1 } else { Expect (Invoke-Execution $runner $ds $plans[$planKey]) 200 "execute $planKey" }
            $rows = Rows $r
            Assert ($rows -eq $expected[$planKey]) "rows $rows <> expected $($expected[$planKey])"
            Assert ($r.Json['validity'] -eq 'CURRENT' -and -not $r.Json['truncated']) 'validity/truncated'
            "rows=$rows"
        }
    }

    # ------------------------------------------------------------ 7. no arbitrary SQL, no other source
    Check '7.1' 'Bodies carrying SQL, plans, tenants or connection data are refused (400)' {
        $bodies = @('{"sql":"SELECT * FROM pg_authid"}', '{"plan":{"formatVersion":"nexora-logical-plan/1"}}',
                    "{`"tenantId`":`"$otherTenant`"}", '{"host":"169.254.169.254","port":80,"password":"x"}',
                    '{"maxRows":"5"}', '{"maxRows":1,"maxRows":2}')
        foreach ($b in $bodies) {
            $r = Invoke-Execution $runner $ds $plans.P1 $b
            Assert ($r.Status -eq 400) "$b -> HTTP $($r.Status)"
        }
        "$($bodies.Count) bodies -> 400"
    }
    Check '7.2' 'A query string with SQL is ignored and nothing but the authorized plan runs' {
        $r = Invoke-Http -Method POST -Url "$ReportsUrl/api/v1/data-sources/$ds/plans/$($plans.P6)/executions?sql=DELETE%20FROM%20plant.equipment" -Token $runner -NoBody
        Assert ($r.Status -eq 200 -and (Rows $r) -eq $expected.P6) "HTTP $($r.Status)"
        $count = Invoke-Sql $SyntheticDatabase 'SELECT count(*) FROM plant.equipment'
        Assert ($count.Output -eq '3') "equipment rows: $($count.Output)"
        'same result as P6; plant.equipment still has 3 rows'
    }
    Check '7.3' 'The enterprise role used by Reports cannot write' {
        $w = Invoke-Sql $SyntheticDatabase "BEGIN; SET ROLE nexora_reader; INSERT INTO plant.equipment VALUES (999, 999, 'x'); ROLLBACK;"
        Assert ($w.ExitCode -ne 0 -and $w.Output -match 'permission denied') "exit=$($w.ExitCode) $($w.Output)"
        'INSERT as nexora_reader -> permission denied'
    }
    Check '7.4' 'An unknown plan id is not found' {
        $r = Invoke-Execution $runner $ds ([guid]::NewGuid())
        Assert ($r.Status -eq 404) "HTTP $($r.Status)"
        'HTTP 404'
    }

    # ------------------------------------------------------------ 8. limits and audit
    Check '8.1' 'maxRows truncates deterministically and is reported' {
        $r = Expect (Invoke-Execution $runner $ds $plans.P6 '{"maxRows":1}') 200 'maxRows 1'
        Assert ((Rows $r) -eq '[["descartada",1]]' -and $r.Json['truncated'] -and $r.Json['warnings'][0]['code'] -eq 'RESULT_TRUNCATED') (Rows $r)
        $over = Invoke-Execution $runner $ds $plans.P6 '{"maxRows":10001}'
        Assert ($over.Status -eq 400) "ceiling: HTTP $($over.Status)"
        'maxRows=1 -> 1 row, truncated, RESULT_TRUNCATED; maxRows=10001 -> 400'
    }
    Check '8.2' 'A locked table makes the execution time out (lock_timeout) and nothing is returned' {
        $job = Start-Job -ScriptBlock {
            $p = @($using:Psql)
            & $p[0] $p[1..($p.Length - 1)] -d $using:SyntheticDatabase -c 'BEGIN; LOCK TABLE finance.transactions IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(12); COMMIT;' 2>&1
        }
        $locked = $false
        foreach ($n in 1..60) {
            $l = Invoke-Sql $SyntheticDatabase "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation WHERE c.relname = 'transactions' AND l.mode = 'AccessExclusiveLock' AND l.granted"
            if ($l.Output -eq '1') { $locked = $true; break }
            Start-Sleep -Milliseconds 250
        }
        Assert $locked 'could not take the test lock'
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $r = Invoke-Execution $runner $ds $plans.P2
        $sw.Stop()
        Wait-Job $job -Timeout 30 | Out-Null; Remove-Job $job -Force
        Assert ($r.Status -eq 504 -and $r.Json['code'] -eq 'execution_timeout') "HTTP $($r.Status) $($r.Json['code'])"
        $after = Expect (Invoke-Execution $runner $ds $plans.P2) 200 'after the lock'
        Assert ((Rows $after) -eq $expected.P2) 'result after the lock'
        "HTTP 504 execution_timeout after $([int]$sw.Elapsed.TotalMilliseconds) ms; succeeds again once the lock is released"
    }

    # ------------------------------------------------------------ 6. STALE (changes Semantic: last)
    Check '6.1' 'A plan made STALE in Semantic is refused by Reports' {
        Expect (Invoke-Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$ds/relationships/$sensorsRel/deactivate" -Token $approver -Headers @{ 'If-Match' = '"1"' } -Body @{ reason = 'R1 e2e validation: make plan STALE' }) 200 'deactivate relationship' | Out-Null
        $validity = (Expect (Invoke-Http -Url "$SemanticUrl/api/v1/data-sources/$ds/logical-plans/$($plans.P3)/validity" -Token $service) 200 'validity').Json
        Assert ($validity['status'] -eq 'STALE') "Semantic says $($validity['status'])"
        $r = Invoke-Execution $runner $ds $plans.P3
        Assert ($r.Status -eq 409 -and $r.Json['code'] -eq 'plan_stale') "HTTP $($r.Status) $($r.Json['code'])"
        $still = Expect (Invoke-Execution $runner $ds $plans.P2) 200 'unaffected plan'
        "Semantic validity=STALE ($($validity['issues'][0]['code'])) -> Reports 409 plan_stale; unaffected P2 still 200"
    }

    Check '8.3' 'Every execution of the run is audited, per tenant, without business data, append-only' {
        $summary = Invoke-Sql 'nexora_reports' "SELECT string_agg(outcome || ':' || coalesce(error_code, '-') || '=' || n, ', ' ORDER BY outcome, error_code) FROM (SELECT outcome, error_code, count(*) n FROM reports.report_execution_audit WHERE started_at >= '$runStart' GROUP BY outcome, error_code) s"
        Assert ($summary.Output -match 'SUCCEEDED' -and $summary.Output -match 'PLAN_STALE' -and $summary.Output -match 'EXECUTION_TIMEOUT') $summary.Output
        $leak = Invoke-Sql 'nexora_reports' "SELECT count(*) FROM reports.report_execution_audit a WHERE started_at >= '$runStart' AND row_to_json(a)::text ~* '(revisar|descartada|Factura|Bomba|Compresor|200[.]5|SELECT|password)'"
        Assert ($leak.Output -eq '0') "rows with business data: $($leak.Output)"
        $tenants = Invoke-Sql 'nexora_reports' "SELECT string_agg(DISTINCT tenant_id::text, ',') FROM reports.report_execution_audit WHERE started_at >= '$runStart'"
        Assert (($tenants.Output -split ',' | Sort-Object) -join ',' -eq ((@($demoTenant, $otherTenant) | Sort-Object) -join ',')) "tenants: $($tenants.Output)"
        $tamper = Invoke-Sql 'nexora_reports' "UPDATE reports.report_execution_audit SET row_count = 0 WHERE execution_id = '$($script:p1.Json['executionId'])'"
        Assert ($tamper.ExitCode -ne 0 -and $tamper.Output -match 'append-only') "update: $($tamper.Output)"
        "outcomes: $($summary.Output); no business data; tenants demo+other separated; UPDATE refused (append-only)"
    }
}
finally {
    if ($reportsProcess) { Stop-Process -Id $reportsProcess.Id -Force -ErrorAction SilentlyContinue }
}

# ---------------------------------------------------------------- report

$failed = @($checks | Where-Object Result -eq 'FAIL').Count
$md = [System.Text.StringBuilder]::new()
[void]$md.AppendLine("# Nexora Reports R1 — end-to-end validation $runId")
[void]$md.AppendLine()
[void]$md.AppendLine("- Keycloak: $KeycloakUrl (realm $Realm) · Semantic: $SemanticUrl · Discovery: $DiscoveryUrl · Reports: $ReportsUrl")
[void]$md.AppendLine("- Tenant demo: $demoTenant · tenant other: $otherTenant · data sources: $ds (demo), $dsOther (other)")
[void]$md.AppendLine("- Plans: " + (($plans.Keys | ForEach-Object { "$_=$($plans[$_])" }) -join ', '))
[void]$md.AppendLine("- Result: **$($checks.Count - $failed) PASS, $failed FAIL**")
[void]$md.AppendLine()
[void]$md.AppendLine('| Id | Check | Result | Evidence |')
[void]$md.AppendLine('|---|---|---|---|')
foreach ($c in $checks) {
    [void]$md.AppendLine("| $($c.Id) | $($c.Check) | $($c.Result) | $(($c.Detail -replace '\|', '/') -replace "`r?`n", ' ') |")
}
$mdPath = Join-Path $outDir "r1-e2e-$runId.md"
$md.ToString() | Set-Content -Encoding utf8 $mdPath
$checks | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $outDir "r1-e2e-$runId.json")
Write-Host "`n$($checks.Count - $failed) PASS, $failed FAIL. Report: $mdPath" -ForegroundColor $(if ($failed) { 'Red' } else { 'Green' })
if ($failed) { exit 1 }
