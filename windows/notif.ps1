$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Sq {
  const string D = "winsqlite3.dll";
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern int sqlite3_open_v2(byte[] f, out IntPtr db, int flags, IntPtr vfs);
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern int sqlite3_prepare_v2(IntPtr db, byte[] sql, int n, out IntPtr st, IntPtr tail);
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern int sqlite3_step(IntPtr st);
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern long sqlite3_column_int64(IntPtr st, int i);
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern IntPtr sqlite3_column_blob(IntPtr st, int i);
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern IntPtr sqlite3_column_text(IntPtr st, int i);
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern int sqlite3_column_bytes(IntPtr st, int i);
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern int sqlite3_finalize(IntPtr st);
  [DllImport(D, CallingConvention = CallingConvention.Cdecl)] public static extern int sqlite3_close(IntPtr db);
  public static byte[] B(string s) { return Encoding.UTF8.GetBytes(s + "\0"); }
  public static string Text(IntPtr st, int i) {
    IntPtr p = sqlite3_column_text(st, i);
    int n = sqlite3_column_bytes(st, i);
    if (p == IntPtr.Zero || n == 0) return "";
    byte[] b = new byte[n];
    Marshal.Copy(p, b, 0, n);
    return Encoding.UTF8.GetString(b);
  }
  public static string Blob(IntPtr st, int i) {
    IntPtr p = sqlite3_column_blob(st, i);
    int n = sqlite3_column_bytes(st, i);
    if (p == IntPtr.Zero || n == 0) return "";
    byte[] b = new byte[n];
    Marshal.Copy(p, b, 0, n);
    if (n > 1 && b[0] == 0xFF && b[1] == 0xFE) return Encoding.Unicode.GetString(b);
    return Encoding.UTF8.GetString(b);
  }
}
'@

$src = Join-Path $env:LOCALAPPDATA 'Microsoft\Windows\Notifications\wpndatabase.db'
if (-not (Test-Path -LiteralPath $src)) { Write-Output 'ERR:no-db'; exit 1 }
$tmp = Join-Path $env:TEMP 'myhelper_wpn'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$apps = @{}
function Load-Apps {
  try { Get-StartApps | ForEach-Object { $script:apps[$_.AppID] = $_.Name } } catch {}
}
Load-Apps

function Copy-Shared($from, $to) {
  if (-not (Test-Path -LiteralPath $from)) {
    if (Test-Path -LiteralPath $to) { Remove-Item -LiteralPath $to -Force }
    return
  }
  $in = [System.IO.File]::Open($from, 'Open', 'Read', 'ReadWrite,Delete')
  try {
    $out = [System.IO.File]::Create($to)
    try { $in.CopyTo($out) } finally { $out.Dispose() }
  } finally { $in.Dispose() }
}

function Disp($a) {
  if ($apps.ContainsKey($a)) { return $apps[$a] }
  $n = $a
  if ($n.Contains('\')) { return [System.IO.Path]::GetFileNameWithoutExtension($n) }
  if ($n.Contains('!')) { $n = $n.Split('!')[0] }
  if ($n.Contains('_')) { $n = $n.Split('_')[0] }
  $p = $n.Split('.')
  return $p[$p.Length - 1]
}

$last = -1
$cycle = 0
$reported = ''
while ($true) {
  try {
    $cycle++
    if ($cycle % 20 -eq 0) { Load-Apps }
    Copy-Shared $src (Join-Path $tmp 'w.db')
    Copy-Shared ($src + '-wal') (Join-Path $tmp 'w.db-wal')
    $db = [IntPtr]::Zero
    $rc = [Sq]::sqlite3_open_v2([Sq]::B((Join-Path $tmp 'w.db')), [ref]$db, 2, [IntPtr]::Zero)
    if ($rc -ne 0) { throw ('open-' + $rc) }
    try {
      if ($last -lt 0) {
        $sql = 'SELECT IFNULL(MAX(Id),0) FROM Notification'
      } else {
        $sql = 'SELECT n.Id, h.PrimaryId, n.Payload FROM Notification n JOIN NotificationHandler h ON n.HandlerId = h.RecordId WHERE n.Id > ' + $last + ' ORDER BY n.Id'
      }
      $st = [IntPtr]::Zero
      $rc = [Sq]::sqlite3_prepare_v2($db, [Sq]::B($sql), -1, [ref]$st, [IntPtr]::Zero)
      if ($rc -ne 0) { throw ('prepare-' + $rc) }
      try {
        if ($last -lt 0) {
          $last = 0
          if ([Sq]::sqlite3_step($st) -eq 100) { $last = [Sq]::sqlite3_column_int64($st, 0) }
          Write-Output 'OK:ready'
        } else {
          while ([Sq]::sqlite3_step($st) -eq 100) {
            $id = [Sq]::sqlite3_column_int64($st, 0)
            $app = [Sq]::Text($st, 1)
            $payload = ([Sq]::Blob($st, 2)).TrimStart([char]0xFEFF)
            if ($id -gt $last) { $last = $id }
            if ($payload -notmatch '<toast') { continue }
            $launch = ''
            $at = ''
            try {
              $x = [xml]$payload
              $launch = [string]$x.toast.launch
              $at = [string]$x.toast.activationType
            } catch {}
            $obj = [pscustomobject]@{ id = [int64]$id; app = $app; name = (Disp $app); launch = $launch; at = $at }
            Write-Output ($obj | ConvertTo-Json -Compress)
          }
        }
      } finally { [void][Sq]::sqlite3_finalize($st) }
    } finally { [void][Sq]::sqlite3_close($db) }
    if ($reported -ne '') { Write-Output 'OK:ready'; $reported = '' }
  } catch {
    $msg = [string]$_.Exception.Message
    if ($msg -ne $reported) { Write-Output ('ERR:' + $msg); $reported = $msg }
  } finally {
    Remove-Item -Path (Join-Path $tmp 'w.db*') -Force -ErrorAction SilentlyContinue
  }
  Start-Sleep -Seconds 3
}
