param([switch]$Window)
$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (!(Test-Path -LiteralPath $envPath)) { throw 'Local .env is missing. Initialize the project first.' }
function Save-KnowFlowKey([string]$value) {
    $key = $value.Trim()
    if ($key.Length -ge 2) {
        $first=$key[0]; $last=$key[$key.Length-1]
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) { $key=$key.Substring(1,$key.Length-2).Trim() }
    }
    if ($key.Contains('*')) { throw 'This looks like a masked key (asterisks). Copy the full original key, not the hidden value in the key list. No file changed.' }
    if ($key -match '^(DASHSCOPE_API_KEY\s*=|Bearer\s+)') { throw 'Paste only the key itself, without DASHSCOPE_API_KEY= or Bearer. No file changed.' }
    if ($key.Length -lt 16 -or $key.Length -gt 8192) { throw 'Key is empty, incomplete or too long. Paste the full key only (16-8192 characters). No file changed.' }
    if ($key -match '[^\x21-\x7E]') { throw 'Key contains internal whitespace or non-ASCII characters. Copy the full key again. No file changed.' }
    $updated=New-Object 'System.Collections.Generic.List[string]'; $found=$false
    foreach($line in [IO.File]::ReadAllLines($envPath)) {
        if($line -match '^DASHSCOPE_API_KEY=') {
            if(!$found){$updated.Add('DASHSCOPE_API_KEY='+$key);$found=$true}
        }else{$updated.Add($line)}
    }
    if(!$found){$updated.Add('DASHSCOPE_API_KEY='+$key)}
    [IO.File]::WriteAllLines($envPath,$updated.ToArray(),[Text.UTF8Encoding]::new($false))
    $key=$null
}
if($Window) {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $form=New-Object Windows.Forms.Form
    $form.Text='KnowFlow - 百炼密钥设置'
    $form.ClientSize=New-Object Drawing.Size(570,220)
    $form.StartPosition='CenterScreen';$form.FormBorderStyle='FixedDialog'
    $form.MaximizeBox=$false;$form.MinimizeBox=$false;$form.TopMost=$true
    $form.Font=New-Object Drawing.Font('Microsoft YaHei UI',10)
    $label=New-Object Windows.Forms.Label
    $label.Text="复制百炼北京区的完整通用 API Key，再点[粘贴]。`n输入将显示为圆点；不会读取或显示原有密钥。"
    $label.Location=New-Object Drawing.Point(18,18);$label.Size=New-Object Drawing.Size(530,52)
    $box=New-Object Windows.Forms.TextBox
    $box.Location=New-Object Drawing.Point(18,85);$box.Size=New-Object Drawing.Size(530,28)
    $box.UseSystemPasswordChar=$true;$box.ShortcutsEnabled=$true;$box.MaxLength=8192
    $paste=New-Object Windows.Forms.Button
    $paste.Text='粘贴';$paste.Location=New-Object Drawing.Point(18,139);$paste.Size=New-Object Drawing.Size(100,35)
    $save=New-Object Windows.Forms.Button
    $save.Text='保存';$save.Location=New-Object Drawing.Point(338,139);$save.Size=New-Object Drawing.Size(100,35)
    $cancel=New-Object Windows.Forms.Button
    $cancel.Text='取消';$cancel.Location=New-Object Drawing.Point(448,139);$cancel.Size=New-Object Drawing.Size(100,35)
    $cancel.DialogResult=[Windows.Forms.DialogResult]::Cancel
    $form.CancelButton=$cancel;$form.AcceptButton=$save
    $paste.Add_Click({
        try { $box.Paste();$box.Focus() }
        catch { [void][Windows.Forms.MessageBox]::Show($form,'无法读取剪贴板，请重新复制完整密钥后再试。','粘贴失败') }
    })
    $save.Add_Click({
        try {
            Save-KnowFlowKey $box.Text
            $box.Clear()
            [void][Windows.Forms.MessageBox]::Show($form,'密钥已保存到本机。尚未验证接口是否可用，请回到聊天告诉我[已保存]。','保存完成')
            $form.DialogResult=[Windows.Forms.DialogResult]::OK;$form.Close()
        } catch {
            # Validation messages are fixed strings; no provider response or key is shown.
            [void][Windows.Forms.MessageBox]::Show($form,'保存失败。请粘贴完整密钥，不要带星号、内部空格或变量名；同时确认本地配置文件可写。','请检查输入')
        }
    })
    $form.Controls.AddRange(@($label,$box,$paste,$save,$cancel))
    $form.Add_Shown({$box.Focus()})
    try { [void]$form.ShowDialog() } finally { $box.Clear();$form.Dispose() }
    return
}
$secret=Read-Host 'Paste the FULL DashScope API Key, then Enter (hidden; never send it in chat)' -AsSecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try { $plainKey=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer);Save-KnowFlowKey $plainKey }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$plainKey=$null;$secret.Dispose() }
Write-Host 'Key saved locally. This confirms storage only; provider authentication still needs verification. Restart the backend to load it.'