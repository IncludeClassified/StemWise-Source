function Get-StemWiseCommandSpecification {
    # Command and dependency phase share one current action catalogue.
    return [ordered]@{
        Help = @{ Command = $null; Phase = 'help'; Recording = $false }
        Doctor = @{ Command = 'doctor'; Phase = 'doctor'; Recording = $false }
        Plan = @{ Command = 'plan'; Phase = 'maintenance'; Recording = $false }
        Reset = @{ Command = 'reset'; Phase = 'maintenance'; Recording = $false }
        Reprocess = @{ Command = 'reprocess'; Phase = 'minimal'; Recording = $true }
        New = @{ Command = 'new'; Phase = 'acquisition'; Recording = $true }
        Source = @{ Command = 'source'; Phase = 'minimal'; Recording = $true }
        Artwork = @{ Command = 'artwork'; Phase = 'minimal'; Recording = $true }
        Task = @{ Command = 'task'; Phase = 'minimal'; Recording = $true }
        Status = @{ Command = 'status'; Phase = 'minimal'; Recording = $true }
        BridgeDelivery = @{ Command = 'bridgedelivery'; Phase = 'minimal'; Recording = $true }
        Validate = @{ Command = 'validate'; Phase = 'minimal'; Recording = $true }
        Finalize = @{ Command = 'finalize'; Phase = 'minimal'; Recording = $true }
        Retry = @{ Command = 'retry'; Phase = 'minimal'; Recording = $true }
        ReplaceAnalysis = @{ Command = 'replaceanalysis'; Phase = 'minimal'; Recording = $true }
        EndpointEvidence = @{ Command = 'endpointevidence'; Phase = 'minimal'; Recording = $true }
        EndpointAdjudicate = @{ Command = 'endpointadjudicate'; Phase = 'endpoint'; Recording = $true }
        RecordingEvidence = @{ Command = 'recordingevidence'; Phase = 'recognition'; Recording = $true }
        SyllaCandidate = @{ Command = 'syllacandidate'; Phase = 'sylla'; Recording = $true }
        AnalyzeRecording = @{ Command = 'analyze'; Phase = 'analysis'; Recording = $false }
        RepairRecording = @{ Command = 'repair'; Phase = 'analysis'; Recording = $false }
        RecordingStatus = @{ Command = 'status'; Phase = 'analysis-status'; Recording = $false }
    }
}
