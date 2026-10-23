# Fixed local speech bridge. stdin fields are data, never PowerShell source or SSML.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
$synth = $null
function Decode-Field([string]$value) {
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
}
function Encode-Field([string]$value) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value))
}
try {
    $operation = [Console]::In.ReadLine()
    $voiceName = Decode-Field ([Console]::In.ReadLine())
    $rate = [int]([Console]::In.ReadLine())
    $text = Decode-Field ([Console]::In.ReadLine())
    $wavePath = Decode-Field ([Console]::In.ReadLine())
    if ($operation -notin @('voices', 'speak', 'wave')) { throw 'INVALID_REQUEST' }
    Add-Type -AssemblyName System.Speech
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $synth.SetOutputToNull()
    $voices = @($synth.GetInstalledVoices() | Where-Object {
        $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en'
    })
    if ($operation -eq 'voices') {
        foreach ($voice in $voices) {
            [Console]::Out.WriteLine("VOICE`t" + (Encode-Field $voice.VoiceInfo.Name) + "`t" + (Encode-Field $voice.VoiceInfo.Culture.Name))
        }
    } else {
        if ($voices.Count -eq 0) { throw 'NO_ENGLISH_VOICE' }
        if (-not ($voices | Where-Object { $_.VoiceInfo.Name -ceq $voiceName })) { throw 'VOICE_UNAVAILABLE' }
        if ($rate -lt -3 -or $rate -gt 3 -or [string]::IsNullOrWhiteSpace($text) -or $text.Length -gt 4000) { throw 'INVALID_REQUEST' }
        $synth.SelectVoice($voiceName)
        $synth.Rate = $rate
        if ($operation -eq 'wave') {
            if (-not [IO.Path]::IsPathRooted($wavePath)) { throw 'INVALID_REQUEST' }
            $synth.SetOutputToWaveFile($wavePath)
        } else {
            $synth.SetOutputToDefaultAudioDevice()
        }
        # Speak(string) treats markup and shell punctuation as literal text.
        $synth.Speak($text)
        $synth.SetOutputToNull()
    }
    [Console]::Out.WriteLine('OK')
} catch {
    $code = $_.Exception.Message
    if ($code -notin @('NO_ENGLISH_VOICE', 'VOICE_UNAVAILABLE', 'INVALID_REQUEST')) { $code = 'ENGINE_UNAVAILABLE' }
    [Console]::Out.WriteLine("ERROR`t" + $code)
    exit 1
} finally {
    if ($null -ne $synth) { $synth.Dispose() }
}
