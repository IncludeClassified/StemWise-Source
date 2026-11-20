package backend

import (
	"errors"
	"net/http"
)

type wakeResponseWriter struct{ http.ResponseWriter }

type appFailure struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
	Action    string `json:"action"`
}

var appFailures = map[string]appFailure{
	"power_shutdown_pending":                {Message: "A shutdown was claimed for the current PC transition.", Retryable: true, Action: "Observe power status; explicitly wake after the PC is off. Reuse an existing accepted job ID without resubmitting work."},
	"power_state_unconfirmed":               {Message: "The bridge power state could not be confirmed.", Retryable: true, Action: "Inspect bridge storage and power status. Automatic shutdown remains disabled."},
	"power_cycle_changed":                   {Message: "The power cycle changed.", Action: "Read power status and use its current cycle ID."},
	"power_issue_unresolved":                {Message: "Current work still has an unresolved issue.", Action: "Investigate or explicitly discard the affected work before releasing the power hold."},
	"invalid_power_request":                 {Message: "The power request is invalid.", Action: "Provide the current cycleId for an explicit hold release."},
	"pc_cleanup_status_unavailable":         {Message: "The PC's native cleanup status is unavailable.", Retryable: true, Action: "Read connection status after the PC returns; cleanup retries automatically and never replays a job."},
	"chat_cleanup_unavailable":              {Message: "The PC could not complete an owned-chat cleanup pass.", Retryable: true, Action: "The bridge retains minimal ownership records and retries. Inspect PC storage/native availability; never delete unrelated chats or resubmit old jobs."},
	"invalid_delivery_receipt":              {Message: "Provide the exact published file descriptor batch after importing it into app storage.", Action: "Verify saved destination bytes and send {files:[...]} to the job delivery endpoint."},
	"delivery_unverified":                   {Message: "This job does not have a verified successful output batch matching the receipt.", Action: "Read the original job; import and verify every published output before acknowledging delivery. Never acknowledge failed or uncertain work."},
	"chat_ownership_unconfirmed":            {Message: "Native chat ownership could not be established from the immutable creation receipt.", Action: "Preserve the original request; the bridge will never delete a chat based on its name or a supplied ID."},
	"chat_cleanup_unconfirmed":              {Message: "Native deletion remains unconfirmed.", Retryable: true, Action: "The bridge retries deletion of the original owned chat when the PC returns. This does not replay the job."},
	"chat_cleanup_delegation_unconfirmed":   {Message: "The PC has not confirmed durable cleanup acceptance for this delivered job.", Retryable: true, Action: "The original Pi job and files remain retained. The bridge retries exact cleanup when PC storage/connectivity returns; it never replays the job."},
	"creation_deleted":                      {Message: "This request's owned chat was already deleted.", Action: "Do not recreate expired or delivered work."},
	"download_capacity_unavailable":         {Message: "The Pi's concurrent download capacity is in use; no file bytes were sent.", Retryable: true, Action: "Retry downloading the same output descriptor after an in-flight download finishes. Do not resubmit the job or request wake."},
	"artifact_belongs_to_another_job":       {Message: "A declared output ID belongs to another job; this job's batch is not published.", Action: "Use fresh output IDs for each job. The original owning job and its verified files are preserved."},
	"output_id_conflict":                    {Message: "A declared file reuses this job's reserved inline-result ID.", Action: "Allocate a fresh transfer ID for the file; do not reuse reserved result IDs."},
	"developer_terminal_report_missing":     {Message: "Native execution ended without a completed or failed developer report; the task outcome is unconfirmed.", Action: "Read the original inputs and diagnostic log. Repair the developer's final-report workflow; do not infer success or replay unverified side effects."},
	"creation_observation_unavailable":      {Message: "The PC could not confirm this creation request.", Retryable: true, Action: "The bridge rechecks the original request's receipt after connectivity returns; do not create a replacement."},
	"creation_receipt_unconfirmed":          {Message: "A confirmed native creation receipt is not available for this exact request.", Retryable: true, Action: "Keep the original creation ID. The bridge only reads receipts and will not repeat an uncertain native creation."},
	"creation_native_unconfirmed":           {Message: "The receipt's exact native chat and project could not be verified.", Retryable: true, Action: "The bridge rechecks the same native ID; it will not guess another chat or create a replacement."},
	"creation_store_unavailable":            {Message: "The PC's managed creation receipt storage could not be read or committed safely.", Retryable: true, Action: "The original reservation remains unknown; restore PC storage access before receipt observation can succeed."},
	"creation_request_invalid":              {Message: "The PC rejected the immutable creation envelope.", Action: "Use the documented creation request fields and preserve its original ID; no replacement is automatically created."},
	"creation_request_conflict":             {Message: "This creation ID belongs to different immutable parameters on the PC.", Action: "Preserve the original app, request ID, timestamp, name and captured model. Do not retry changed parameters."},
	"creation_not_sent":                     {Message: "The PC confirmed that native chat creation was not submitted and released the exact reservation.", Retryable: true, Action: "Keep the original job and unchanged inputs. The bridge observes the original reservation and retries creation only after authenticated absence; do not submit a replacement job."},
	"creation_rejected":                     {Message: "Native chat creation was explicitly rejected and the PC released the exact reservation.", Retryable: true, Action: "Inspect the independent job log and repair the native rejection. The bridge observes the original reservation and retries the same creation only after authenticated absence; keep its captured settings and original job ID."},
	"creation_capacity_reached":             {Message: "The paired PC has reached its bounded capacity for unresolved native job owners.", Retryable: true, Action: "Observe pending cleanup and allow fixed expiry to retire old owners. Keep the original queued job and its immutable request ID; never submit a replacement."},
	"job_cleanup_authorization_unconfirmed": {Message: "The PC has not confirmed durable cleanup authorization for this exact job owner.", Retryable: true, Action: "Retry deleting the original job after PC storage/connectivity returns. Its Pi record and required files are retained; do not submit another job or infer that native work stopped."},
	"dispatch_observation_unavailable":      {Message: "The submission acknowledgment was lost and native history is currently unavailable.", Retryable: true, Action: "The bridge will recheck native history after connectivity returns; keep the original request ID."},
	"dispatch_history_unconfirmed":          {Message: "Native history could not establish a unique submission within the bounded search.", Retryable: true, Action: "The bridge preserves the unknown outcome and rechecks without submitting another turn."},
	"dispatch_match_ambiguous":              {Message: "More than one native turn contains this job's exact submitted inputs.", Action: "The bridge preserves the unknown outcome and will not choose a turn or repeat execution."},
	"pc_unreachable":                        {Message: "The PC is currently unreachable; this job remains queued.", Retryable: true, Action: "Observe connection status. A new explicit user Wake, Send to Bridge, Retry or settings Wake action may authorize one wake request when needed. Preserve the original job ID; passive recovery and automatic retries never authorize wake."},
	"developer_busy":                        {Message: "The chosen developer is processing earlier work; this job remains queued.", Retryable: true, Action: "The bridge will retry after the developer becomes idle."},
	"developer_selection_unconfirmed":       {Message: "Codex returned a different or malformed developer selection.", Action: "Inspect this job's original native creation and selection diagnostic."},
	"developer_state_unavailable":           {Message: "The chosen developer is not available for direct input.", Action: "Inspect this job's original native chat diagnostic; never replay unknown work."},
	"developer_selection_rejected":          {Message: "Codex rejected selection of this developer.", Action: "Inspect this job's original native chat diagnostic."},
	"native_submission_rejected":            {Message: "Codex rejected this job before confirming a turn.", Action: "Check the chosen CLI chat and current model settings."},
	"invalid_developer_protocol":            {Message: "The developer returned a message that does not satisfy the bridge report protocol.", Action: "The app must treat the task outcome as unconfirmed; no output batch is published."},
	"input_delivery_unconfirmed":            {Message: "The PC has not confirmed all input file bytes; the prompt remains unsent.", Retryable: true, Action: "The bridge will retry the same file receipts when the PC is reachable."},
	"manual_wake_required":                  {Message: "Wake requires an explicit user action.", Action: "Send one empty-body POST /v2/connection/wake only for an explicit user Wake, Send to Bridge, Retry or settings Wake action. Durably claim at most one attempt for that action; agents, job execution, passive polling, reconnect, startup and background recovery never authorize wake."},
	"wake_unavailable":                      {Message: "The Pi's wake sender is not configured.", Action: "Configure the Pi's PC and LAN pairing."},
	"invalid_wake_request":                  {Message: "The wake endpoint requires an empty request body.", Action: "Send one empty-body POST only for an explicit user Wake, Send to Bridge, Retry or settings Wake action; do not attach jobs, prompts or settings."},
	"wake_unconfirmed":                      {Message: "The Pi could not confirm sending the wake packet.", Action: "Observe current connection status; an unconfirmed acknowledgment does not prove that no packet was sent."},
	"browser_origin_forbidden":              {Message: "This private API accepts native app requests.", Action: "Use the embedded bridge client."},
	"unpaired_app_identity":                 {Message: "This app installation has not been paired with this PC.", Action: "Complete app enrollment and PC pairing."},
	"bridge_stopping":                       {Message: "The bridge is restarting or stopping.", Retryable: true, Action: "Reconnect and read the saved request status."},
	"invalid_route":                         {Message: "The endpoint or its parameters are invalid.", Action: "Use an endpoint documented in the API contract."},
	"invalid_cursor":                        {Message: "The catalog cursor is invalid.", Action: "Use the cursor returned by the previous catalog page."},
	"unsupported_route":                     {Message: "This method and endpoint are not supported.", Action: "Use a method listed in the API contract."},
	"invalid_app":                           {Message: "Provide an app name of 1–255 bytes without control characters.", Action: "Correct the app registration request."},
	"app_capacity_reached":                  {Message: "The app's retained metadata has no safe capacity for this update, including space reserved for unfinished work.", Action: "Import needed outputs, delete finished jobs or saved prompts, then retry the original request ID and unchanged input. Do not create replacement native work."},
	"app_store_unavailable":                 {Message: "The Pi could not safely read or save the app's data.", Retryable: true, Action: "Restore Pi storage access and retry the same request ID; do not create a replacement job."},
	"app_identity_already_registered":       {Message: "This bridge installation is already registered to another app name.", Action: "Use its existing registration or a separate app installation identity."},
	"app_registration_required":             {Message: "This app has not registered with the bridge.", Action: "Register through POST /v2/app."},
	"app_registration_changed":              {Message: "The app registration changed while the request body was being received.", Action: "Read the current registration. Preserve original request IDs and reconcile accepted work; never apply the suspended request to a replacement registration."},
	"app_request_cancelled":                 {Message: "The caller cancelled the request before its body could be admitted.", Action: "Preserve original request IDs and observe their authoritative records before any later user-approved attempt. Cancellation never authorizes replay or wake."},
	"invalid_job":                           {Message: "The job fields, timestamp or file descriptors are invalid.", Action: "Correct the request using the documented schema."},
	"request_id_conflict":                   {Message: "This request ID already belongs to different input data.", Action: "Retry with unchanged input, or use a new ID for new work."},
	"request_deleted":                       {Message: "This request was deleted or explicitly retired. Its historical execution outcome is unknown from this receipt.", Action: "Persist this receipt before retiring local tracking. Never resubmit this request or infer that native execution stopped. Preserve app-owned imports and files; use a fresh request ID only for newly approved work. The server's admission barrier has bounded retention."},
	"invalid_retirement_request":            {Message: "The retirement endpoint requires an empty request body.", Action: "Send an empty-body POST to /v2/jobs/<id>/retire for the original unresolved ID. A retained job is returned unchanged."},
	"job_limit_reached":                     {Message: "This app has reached its capacity for live jobs and pending cleanup owners.", Action: "Allow fixed expiry and pending cleanup to finish, or explicitly delete finished work. Never replace an uncertain request with another ID."},
	"input_bytes_unverified":                {Message: "An input file is missing, expired or differs from its descriptor.", Action: "Upload the exact input bytes and use the returned receipt."},
	"model_or_effort_unavailable":           {Message: "The selected model or reasoning effort is absent from the current Codex catalog.", Action: "Choose a model and effort returned by /v2/codex/models."},
	"service_tier_model_required":           {Message: "A service tier requires an explicitly selected or saved model.", Action: "Choose a model before selecting a service tier. The bridge does not guess a default model."},
	"invalid_service_tier_choice":           {Message: "Native-default service tier cannot be combined with an explicit service tier.", Action: "Before admission, choose either nativeDefaultServiceTier:true with an empty serviceTier or one explicit serviceTier. For an accepted job, preserve its original ID and unchanged choices; do not replace its input."},
	"service_tier_unavailable":              {Message: "The selected service tier is absent from this model's confirmed native catalog.", Action: "Choose a service tier advertised for the selected model by /v2/codex/models."},
	"invalid_model_settings":                {Message: "Model settings contain invalid fields.", Action: "Send the documented model, effort and optional serviceTier strings."},
	"model_catalog_unavailable":             {Message: "Codex could not return its model catalog.", Retryable: true, Action: "Retry the catalog request."},
	"codex_unavailable":                     {Message: "The native Codex backend is unavailable.", Retryable: true, Action: "Reconnect and retry reading backend status."},
	"native_auth_required":                  {Message: "Codex authentication is missing or rejected. New native execution is paused and the original job remains queued.", Retryable: true, Action: "Sign in with the intended account using the packaged PC Codex runtime. The bridge verifies authentication before starting work; never replace or replay an accepted job."},
	"native_auth_change_pending":            {Message: "Codex login changed while its owned runtime still has active or uncertain work. New execution is paused.", Retryable: true, Action: "Observe existing jobs. The bridge reloads only its idle owned runtime, then verifies the new login; it never stops unrelated chats or replays existing turns."},
	"native_auth_unavailable":               {Message: "The current Bridge runtime's authentication could not be verified. New execution is paused.", Retryable: true, Action: "Read connection codexAuth and the independent job log. Restore account/provider connectivity; the bridge rechecks authentication without resubmitting uncertain work."},
	"native_auth_source_unverified":         {Message: "The configured native credential source cannot support verified automatic account switching.", Retryable: true, Action: "Use the documented managed ChatGPT file credential source for this installation. Preserve credentials and accepted jobs; do not copy or reset authentication files."},
	"chat_creation_unconfirmed":             {Message: "This job's native chat creation is not yet confirmed; its reservation is retained.", Retryable: true, Action: "Read GET /v2/jobs/<id> for this original job. The bridge observes its original creation receipt; do not create a replacement job or disconnect to erase the unknown outcome."},
	"invalid_prompt":                        {Message: "The saved prompt is empty, too large or has invalid fields.", Action: "Correct the prompt; text and instructions together must fit 64 KiB."},
	"prompt_limit_reached":                  {Message: "This app has 64 saved prompts.", Action: "Update or remove an existing prompt."},
	"job_unavailable":                       {Message: "This job is absent, expired or belongs to another app; its historical execution outcome is unknown.", Action: "Never infer retirement or resubmit accepted or uncertain work from this response. For an original unresolved ID, POST an empty body to /v2/jobs/<id>/retire; preserve any retained job returned, or persist its authoritative request_deleted receipt before retiring local tracking."},
	"job_observation_unavailable":           {Message: "The exact native job outcome could not be established.", Retryable: true, Action: "Retry status observation without resubmitting work."},
	"native_terminal_unconfirmed":           {Message: "The native completion is not committed by the PC adapter.", Retryable: true, Action: "Read the independent job log and restore authoritative history for the original turn. Retry observation only; do not resubmit acknowledged work."},
	"native_interaction_required":           {Message: "Native execution requested client interaction unavailable to unattended bridge jobs.", Action: "Read the job log and original native outcome. The bridge requests interruption of this exact turn; repair the workflow or execution capability before new work. No approval or answer is sent."},
	"output_unavailable":                    {Message: "This output is absent, expired or belongs to another job.", Action: "Use a file ID returned by this job's outputs endpoint."},
	"output_bytes_unverified":               {Message: "The job's complete output batch could not be verified.", Retryable: true, Action: "Retry output observation; do not process a partial batch."},
	"job_output_ownership_unconfirmed":      {Message: "The developer declared outputs without matching durable PC receipts for this exact app, job and chat.", Action: "Inspect the job log and repair the producer to queue every output with the exact authenticated handoff ownership flags before reporting. Preserve the original job; do not replay native work or acknowledge delivery."},
	"retention_cleanup_unavailable":         {Message: "Expired or explicitly discarded bridge data could not be cleaned up safely.", Retryable: true, Action: "Restore Pi storage access and read expiredJobCleanup. Cleanup obligations retry without replaying jobs; app-owned imported files are excluded."},
	"expired_job_cleanup_unconfirmed":       {Message: "An expired job's remaining PC cleanup has not been acknowledged.", Retryable: true, Action: "Restore PC connectivity and storage. Bridge retries its compact cleanup obligation without restoring expired payloads or replaying the job."},
	"expired_job_local_cleanup_pending":     {Message: "Expired job records are removed, but some managed file bytes could not yet be deleted safely.", Retryable: true, Action: "Read expiredJobCleanup and storage diagnostics, restore access to the affected managed storage and allow cleanup to retry. Other jobs continue; app-owned imported files are untouched."},
	"dispatch_outcome_unconfirmed":          {Message: "The bridge cannot yet establish whether Codex received this request.", Retryable: true, Action: "The bridge rechecks exact native inputs automatically; keep the original request ID and do not create another submission."},
	"job_not_interruptible":                 {Message: "The saved job has no supported cancellable lifecycle state.", Action: "Read the original job and its diagnostics without resubmitting it."},
	"cancellation_pending":                  {Message: "Cancellation is saved; retirement of the native turn and its background processes is not yet confirmed.", Retryable: true, Action: "Read the original job status. The bridge recovers the original turn, requests interruption and verifies owned background terminal cleanup without replaying the job."},
	"cancellation_retirement_unconfirmed":   {Message: "The PC has not confirmed retirement of this cancelled job's owned background processes.", Retryable: true, Action: "Read the original job and its diagnostic log. The bridge retries exact owned-thread retirement; it does not replay the job or delete its chat early."},
	"interrupt_unconfirmed":                 {Message: "Codex could not confirm the interrupt request.", Retryable: true, Action: "Read the original job status. The bridge retains cancellation intent and retries the exact turn automatically."},
	"job_not_removable":                     {Message: "This job has active or uncertain native work.", Action: "Observe its outcome before deleting the record."},
	"app_jobs_changed":                      {Message: "The app's retained job owners changed while cleanup was being authorized.", Retryable: true, Action: "Read current jobs and retry disconnect only if discarding their terminal work is still intended. Newly accepted or replaced jobs are preserved; never replay them."},
}

func (s *Service) appStoreError(w http.ResponseWriter, err error) {
	if errors.Is(err, errPowerAdmission) {
		s.appError(w, http.StatusConflict, "power_shutdown_pending")
		return
	}
	if errors.Is(err, errAppCapacity) {
		s.appError(w, http.StatusConflict, "app_capacity_reached")
		return
	}
	s.appError(w, http.StatusServiceUnavailable, "app_store_unavailable")
}

func appErrorValue(code string) appFailure {
	failure, ok := appFailures[code]
	if !ok {
		panic("Undocumented bridge error: " + code)
	}
	failure.Code = code
	return failure
}
