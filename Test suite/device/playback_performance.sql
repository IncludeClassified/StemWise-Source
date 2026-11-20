CREATE PERFETTO TABLE workloads AS
SELECT s.id, s.name, s.ts, s.dur, t.upid
FROM slice s JOIN process_track t ON t.id=s.track_id JOIN process p USING (upid)
WHERE p.name='app.stemwise.development' AND s.name GLOB 'StemWise.test:*';

CREATE PERFETTO TABLE paired_counters AS
SELECT w.id, w.name AS workload, ct.name AS metric,
       LEAD(c.value) OVER (PARTITION BY w.id,ct.id ORDER BY c.ts)-c.value AS delta
FROM workloads w JOIN process_counter_track ct ON ct.upid=w.upid
JOIN counter c ON c.track_id=ct.id AND c.ts BETWEEN w.ts AND w.ts+w.dur
WHERE ct.name IN ('StemWise.cpu_ms','StemWise.bytes-allocated','StemWise.gc-count',
                  'StemWise.blocking-gc-count');

CREATE PERFETTO TABLE cache_io AS
SELECT s.*, t.upid FROM slice s JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t USING (utid) JOIN process p USING (upid)
WHERE p.name='app.stemwise.development' AND s.name GLOB 'StemWise.cache.*';

CREATE PERFETTO TABLE audio_spans AS
SELECT s.*, t.upid, t.utid FROM slice s JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t USING (utid) JOIN process p USING (upid)
WHERE p.name='app.stemwise.development'
AND s.name IN ('StemWise.audio.callback','StemWise.audio.retained_body_copy','StemWise.audio.environment',
 'StemWise.audio.track.platform_observation');

CREATE PERFETTO TABLE environment_snapshots AS
SELECT w.id AS workload_id,s.id,s.ts,s.dur,s.upid,COUNT(c.ts) AS phase_count,MAX(c.value) AS phase
FROM workloads w JOIN audio_spans s ON s.upid=w.upid AND s.name='StemWise.audio.environment'
AND s.ts>=w.ts AND s.dur>=0 AND s.ts+s.dur<=w.ts+w.dur
LEFT JOIN process_counter_track ct ON ct.upid=w.upid AND ct.name='StemWise.environment.phase'
LEFT JOIN counter c ON c.track_id=ct.id AND c.ts BETWEEN s.ts AND s.ts+s.dur
GROUP BY w.id,s.id;

CREATE PERFETTO TABLE callback_samples AS
SELECT w.id AS workload_id, s.id, s.ts, s.dur, s.utid, s.upid,
       COUNT(c.ts) AS counter_count,
       COUNT(DISTINCT CASE WHEN c.ts IS NOT NULL THEN ct.name END) AS counter_names,
       SUM(CASE WHEN c.value<0 OR c.value!=CAST(c.value AS INT) THEN 1 ELSE 0 END) AS invalid_values,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.run' THEN c.value END) AS physical_run,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.sequence' THEN c.value END) AS callback_sequence,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.generation_at_render_decision' THEN c.value END) AS generation_at_render_decision,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.generation_at_completion' THEN c.value END) AS generation_at_completion,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.start_gap_us' THEN c.value END) AS gap_us,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.queue_frames' THEN c.value END) AS queue_frames,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.playing' THEN c.value END) AS playing,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.execution_us' THEN c.value END) AS execution_us,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.deadline_us' THEN c.value END) AS deadline_us,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.requested_frames' THEN c.value END) AS requested_frames,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.physical_frames' THEN c.value END) AS physical_frames,
       MAX(CASE WHEN ct.name='StemWise.audio.callback.valid_frames' THEN c.value END) AS valid_frames
FROM workloads w JOIN audio_spans s ON s.upid=w.upid AND s.name='StemWise.audio.callback'
AND s.ts>=w.ts AND s.dur>=0 AND s.ts+s.dur<=w.ts+w.dur
LEFT JOIN process_counter_track ct ON ct.upid=w.upid AND ct.name IN (
 'StemWise.audio.callback.run','StemWise.audio.callback.sequence','StemWise.audio.callback.start_gap_us',
 'StemWise.audio.callback.queue_frames','StemWise.audio.callback.playing',
 'StemWise.audio.callback.execution_us','StemWise.audio.callback.deadline_us',
 'StemWise.audio.callback.requested_frames','StemWise.audio.callback.physical_frames',
 'StemWise.audio.callback.valid_frames',
 'StemWise.audio.callback.generation_at_render_decision','StemWise.audio.callback.generation_at_completion')
LEFT JOIN counter c ON c.track_id=ct.id AND c.ts BETWEEN s.ts AND s.ts+s.dur
GROUP BY w.id,s.id;

CREATE PERFETTO TABLE valid_callbacks AS
SELECT * FROM callback_samples c WHERE counter_count=12 AND counter_names=12 AND invalid_values=0
AND physical_run>0 AND callback_sequence>0 AND deadline_us>0 AND requested_frames>0 AND playing IN (0,1)
AND generation_at_render_decision>0 AND generation_at_completion>0
AND valid_frames<=physical_frames AND physical_frames<=requested_frames
AND NOT EXISTS (SELECT 1 FROM audio_spans other WHERE other.name='StemWise.audio.callback'
 AND other.upid=c.upid AND other.id!=c.id AND other.ts<c.ts+c.dur
 AND (other.dur<0 OR other.ts+other.dur>c.ts));

CREATE PERFETTO TABLE callback_run_starts AS
SELECT *, CASE WHEN gap_us=0 OR
 LAG(physical_run) OVER (PARTITION BY workload_id ORDER BY ts) IS NULL OR
 physical_run!=LAG(physical_run) OVER (PARTITION BY workload_id ORDER BY ts) OR
 utid!=LAG(utid) OVER (PARTITION BY workload_id ORDER BY ts) THEN 1 ELSE 0 END AS starts_run
FROM valid_callbacks;

CREATE PERFETTO TABLE callback_runs AS
SELECT *, SUM(starts_run) OVER (PARTITION BY workload_id ORDER BY ts) AS run_id
FROM callback_run_starts;

CREATE PERFETTO TABLE ordered_callbacks AS
SELECT *, ROW_NUMBER() OVER (PARTITION BY workload_id,run_id ORDER BY ts) AS run_position,
       LAG(callback_sequence) OVER (PARTITION BY workload_id,run_id ORDER BY ts) AS previous_sequence
FROM callback_runs;

CREATE PERFETTO TABLE callback_run_windows AS
SELECT c.workload_id,c.run_id,c.utid,MIN(c.ts) AS start_ts,
 LEAD(MIN(c.ts),1,w.ts+w.dur) OVER(PARTITION BY c.workload_id ORDER BY MIN(c.ts)) AS end_ts,
 SUM(c.playing=1 AND c.physical_frames>0) AS active_callbacks
FROM ordered_callbacks c JOIN workloads w ON w.id=c.workload_id
GROUP BY c.workload_id,c.run_id,c.utid;

CREATE PERFETTO TABLE platform_samples AS
SELECT w.id AS workload_id,s.id,s.ts,s.utid,COUNT(c.ts) AS counter_count,
 COUNT(DISTINCT CASE WHEN c.ts IS NOT NULL THEN ct.name END) AS counter_names,
 MAX(CASE WHEN ct.name='StemWise.audio.track.platform_session' THEN c.value END) AS session,
 MAX(CASE WHEN ct.name='StemWise.audio.track.platform_raw_head' THEN c.value END) AS raw_head,
 MAX(CASE WHEN ct.name='StemWise.audio.track.platform_submitted' THEN c.value END) AS submitted,
 MAX(CASE WHEN ct.name='StemWise.audio.track.platform_raw_xruns' THEN c.value END) AS xruns,
 MAX(CASE WHEN ct.name='StemWise.audio.track.platform_play_state' THEN c.value END) AS play_state,
 MAX(CASE WHEN ct.name='StemWise.audio.track.platform_route_id' THEN c.value END) AS route_id,
 MAX(CASE WHEN ct.name='StemWise.audio.track.platform_route_type' THEN c.value END) AS route_type
FROM workloads w JOIN audio_spans s ON s.upid=w.upid
AND s.name='StemWise.audio.track.platform_observation' AND s.ts>=w.ts AND s.dur>=0 AND s.ts+s.dur<=w.ts+w.dur
LEFT JOIN process_counter_track ct ON ct.upid=w.upid AND ct.name IN (
 'StemWise.audio.track.platform_session','StemWise.audio.track.platform_raw_head',
 'StemWise.audio.track.platform_submitted','StemWise.audio.track.platform_raw_xruns',
 'StemWise.audio.track.platform_play_state','StemWise.audio.track.platform_route_id',
 'StemWise.audio.track.platform_route_type')
LEFT JOIN counter c ON c.track_id=ct.id AND c.ts BETWEEN s.ts AND s.ts+s.dur
GROUP BY w.id,s.id;

CREATE PERFETTO TABLE platform_predecessors AS
SELECT *,LAG(raw_head) OVER(PARTITION BY workload_id,utid,session ORDER BY ts) AS previous_head
FROM platform_samples;

CREATE PERFETTO TABLE platform_positions AS
SELECT *,raw_head+4294967296*SUM(CASE WHEN raw_head<previous_head AND previous_head-raw_head>2147483648 THEN 1 ELSE 0 END)
 OVER(PARTITION BY workload_id,utid,session ORDER BY ts) AS presented
FROM platform_predecessors;

-- PLAYING with head zero can precede Android route assignment and proves no presentation or route.
CREATE PERFETTO TABLE platform_route_samples AS
SELECT p.*,r.run_id FROM platform_positions p JOIN callback_run_windows r
 ON r.workload_id=p.workload_id AND r.utid=p.utid AND r.active_callbacks>0
 AND p.ts>=r.start_ts AND p.ts<r.end_ts
WHERE p.play_state=3 AND p.presented>0;

CREATE PERFETTO TABLE invalid_platform_routes AS
SELECT p.workload_id FROM platform_positions p WHERE p.play_state=3 AND p.presented>0 AND
 (p.route_id IS NULL OR p.route_type IS NULL OR p.route_id<=0 OR p.route_type<=0 OR
  p.route_id!=CAST(p.route_id AS INT) OR p.route_type!=CAST(p.route_type AS INT) OR
  NOT EXISTS (SELECT 1 FROM platform_route_samples r WHERE r.workload_id=p.workload_id AND r.id=p.id))
UNION ALL
SELECT r.workload_id FROM callback_run_windows r WHERE r.active_callbacks>0 AND NOT EXISTS
 (SELECT 1 FROM platform_route_samples p WHERE p.workload_id=r.workload_id AND p.run_id=r.run_id
  AND p.counter_count=7 AND p.counter_names=7 AND p.session>0 AND p.route_id>0 AND p.route_type>0
  AND p.route_id=CAST(p.route_id AS INT) AND p.route_type=CAST(p.route_type AS INT))
UNION ALL
SELECT workload_id FROM platform_route_samples GROUP BY workload_id,run_id HAVING COUNT(DISTINCT session)!=1;

CREATE PERFETTO TABLE retained_copies AS
SELECT w.id AS workload_id, s.id, s.ts, s.dur, COUNT(c.ts) AS counter_count,
       MAX(c.value) AS bytes
FROM workloads w JOIN audio_spans s ON s.upid=w.upid AND s.name='StemWise.audio.retained_body_copy'
AND s.ts>=w.ts AND s.dur>=0 AND s.ts+s.dur<=w.ts+w.dur
LEFT JOIN process_counter_track ct ON ct.upid=w.upid AND ct.name='StemWise.audio.retained_body_bytes'
LEFT JOIN counter c ON c.track_id=ct.id AND c.ts BETWEEN s.ts AND s.ts+s.dur
GROUP BY w.id,s.id;

CREATE PERFETTO TABLE wakeups AS
SELECT e.ts, t.upid FROM ftrace_event e
JOIN thread t ON t.tid=CAST(EXTRACT_ARG(e.arg_set_id, 'pid') AS INT)
WHERE e.name='sched_waking' AND (t.start_ts IS NULL OR e.ts>=t.start_ts)
AND (t.end_ts IS NULL OR e.ts<=t.end_ts);

SELECT w.id, w.name AS workload, 'wall_ms' AS metric, 1 AS samples,
       w.dur/1e6 AS value, NULL AS max_ms, NULL AS over_10ms, NULL AS over_50ms
FROM workloads w
UNION ALL
SELECT id, workload, metric, COUNT(*), MAX(delta), NULL, NULL, NULL
FROM paired_counters GROUP BY id, metric
UNION ALL
SELECT w.id, w.name, 'native_heap_end_bytes', COUNT(*),
       (SELECT c.value FROM counter c JOIN process_counter_track ct ON ct.id=c.track_id
        WHERE ct.upid=w.upid AND ct.name='StemWise.native_heap_bytes'
        AND c.ts BETWEEN w.ts AND w.ts+w.dur ORDER BY c.ts DESC LIMIT 1), NULL, NULL, NULL
FROM workloads w GROUP BY w.id
UNION ALL
SELECT w.id, w.name, 'scheduler_wakeups', COUNT(e.ts), COUNT(e.ts), NULL, NULL, NULL
FROM workloads w LEFT JOIN wakeups e ON e.upid=w.upid AND e.ts BETWEEN w.ts AND w.ts+w.dur
GROUP BY w.id
UNION ALL
SELECT w.id, w.name, io.name, COUNT(*), SUM(io.dur)/1e6, MAX(io.dur)/1e6,
       SUM(io.dur>=10000000), SUM(io.dur>=50000000)
FROM workloads w JOIN cache_io io ON io.upid=w.upid AND io.ts BETWEEN w.ts AND w.ts+w.dur
WHERE io.dur>=0 AND io.ts+io.dur<=w.ts+w.dur GROUP BY w.id, io.name
UNION ALL
SELECT w.id, w.name, 'incomplete_cache_spans', COUNT(*), COUNT(*), NULL, NULL, NULL
FROM workloads w JOIN cache_io io ON io.upid=w.upid AND io.ts<w.ts+w.dur
AND (io.dur<0 OR io.ts+io.dur>w.ts)
WHERE io.ts<w.ts OR io.dur<0 OR io.ts+io.dur>w.ts+w.dur GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_execution_ms',COUNT(*),SUM(c.execution_us)/1000.0,
       MAX(c.execution_us)/1000.0,SUM(c.execution_us>=10000),SUM(c.execution_us>=50000)
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_start_gap_ms',COUNT(*),SUM(c.gap_us)/1000.0,
       MAX(c.gap_us)/1000.0,SUM(c.gap_us>=10000),SUM(c.gap_us>=50000)
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id AND c.run_position>1 GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_physical_runs',COUNT(DISTINCT c.run_id),COUNT(DISTINCT c.run_id),NULL,NULL,NULL
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_queue_min_frames',COUNT(*),MIN(c.queue_frames),NULL,NULL,NULL
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_requested_frames',COUNT(*),SUM(c.requested_frames),NULL,NULL,NULL
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_physical_frames',COUNT(*),SUM(c.physical_frames),NULL,NULL,NULL
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_valid_frames',COUNT(*),SUM(c.valid_frames),NULL,NULL,NULL
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_playing',COUNT(*),SUM(c.playing),NULL,NULL,NULL
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_deadline_misses',COUNT(*),SUM(c.execution_us>c.deadline_us),NULL,NULL,NULL
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'callback_headroom_min_ms',COUNT(*),MIN(c.deadline_us-c.execution_us)/1000.0,NULL,NULL,NULL
FROM workloads w JOIN ordered_callbacks c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'platform_queue_min_frames',COUNT(*),MIN(p.submitted-p.presented),NULL,NULL,NULL
FROM workloads w JOIN platform_positions p ON p.workload_id=w.id AND p.play_state=3 GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'platform_queue_max_frames',COUNT(*),MAX(p.submitted-p.presented),NULL,NULL,NULL
FROM workloads w JOIN platform_positions p ON p.workload_id=w.id AND p.play_state=3 GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'platform_xrun_max',COUNT(*),MAX(p.xruns),NULL,NULL,NULL
FROM workloads w JOIN platform_positions p ON p.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'invalid_platform_observations',COUNT(*),SUM(CASE WHEN
 p.counter_count!=7 OR p.counter_names!=7 OR p.session IS NULL OR p.raw_head IS NULL OR
 p.submitted IS NULL OR p.xruns IS NULL OR p.play_state IS NULL OR p.route_id IS NULL OR p.route_type IS NULL OR
 p.session<=0 OR p.raw_head<0 OR p.raw_head>=4294967296 OR
 p.submitted<p.presented OR p.xruns<0 OR p.play_state NOT IN(1,2,3) OR
 (p.raw_head<p.previous_head AND p.previous_head-p.raw_head<=2147483648) THEN 1 ELSE 0 END),NULL,NULL,NULL
FROM workloads w JOIN platform_positions p ON p.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'platform_route_id_min',COUNT(*),MIN(p.route_id),NULL,NULL,NULL
FROM workloads w JOIN platform_route_samples p ON p.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'platform_route_id_max',COUNT(*),MAX(p.route_id),NULL,NULL,NULL
FROM workloads w JOIN platform_route_samples p ON p.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'platform_route_type_min',COUNT(*),MIN(p.route_type),NULL,NULL,NULL
FROM workloads w JOIN platform_route_samples p ON p.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'platform_route_type_max',COUNT(*),MAX(p.route_type),NULL,NULL,NULL
FROM workloads w JOIN platform_route_samples p ON p.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'invalid_platform_routes',COUNT(b.workload_id),COUNT(b.workload_id),NULL,NULL,NULL
FROM workloads w LEFT JOIN invalid_platform_routes b ON b.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'StemWise.audio.retained_body_copy',COUNT(*),SUM(c.dur)/1e6,
       MAX(c.dur)/1e6,SUM(c.dur>=10000000),SUM(c.dur>=50000000)
FROM workloads w JOIN retained_copies c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'retained_body_copy_bytes',COUNT(*),SUM(c.bytes),NULL,NULL,NULL
FROM workloads w JOIN retained_copies c ON c.workload_id=w.id GROUP BY w.id
UNION ALL
SELECT w.id,w.name,'excluded_callback_spans',COUNT(*),COUNT(*),NULL,NULL,NULL
FROM workloads w JOIN audio_spans s ON s.upid=w.upid AND s.name='StemWise.audio.callback'
AND s.dur>=0 AND s.ts<w.ts+w.dur AND s.ts+s.dur>w.ts
WHERE s.ts<w.ts OR s.ts+s.dur>w.ts+w.dur GROUP BY w.id
UNION ALL
SELECT w.id,w.name,ct.name||CASE WHEN e.phase=1 THEN '.before' ELSE '.after' END,
       COUNT(c.ts),MAX(c.value),NULL,NULL,NULL
FROM workloads w JOIN environment_snapshots e ON e.workload_id=w.id AND e.phase_count=1 AND e.phase IN (1,2)
JOIN process_counter_track ct ON ct.upid=e.upid AND ct.name IN (
 'StemWise.environment.thermal_status','StemWise.environment.power_save',
 'StemWise.environment.battery_temperature_deci_c','StemWise.environment.battery_level',
 'StemWise.environment.battery_scale','StemWise.output.captured',
 'StemWise.output.sample_rate_hz','StemWise.output.frames_per_burst',
 'StemWise.output.buffer_capacity_frames','StemWise.output.buffer_size_frames',
 'StemWise.output.backend','StemWise.output.performance_mode',
 'StemWise.output.xrun_count','StemWise.output.physical_run',
 'StemWise.output.observation_kind','StemWise.output.observation_received_nanos')
JOIN counter c ON c.track_id=ct.id AND c.ts BETWEEN e.ts AND e.ts+e.dur
GROUP BY w.id,e.phase,ct.name
UNION ALL
SELECT w.id,w.name,'invalid_audio_spans',COUNT(*),COUNT(*),NULL,NULL,NULL
FROM workloads w JOIN (
 SELECT c.workload_id FROM callback_samples c WHERE NOT EXISTS
  (SELECT 1 FROM valid_callbacks v WHERE v.id=c.id AND v.workload_id=c.workload_id)
 UNION ALL
 SELECT workload_id FROM ordered_callbacks WHERE previous_sequence>=callback_sequence
 UNION ALL
 SELECT workload_id FROM retained_copies WHERE counter_count!=1 OR bytes<0 OR bytes!=CAST(bytes AS INT)
 UNION ALL
 SELECT workload_id FROM environment_snapshots WHERE phase_count!=1 OR phase NOT IN (1,2)
 UNION ALL
 SELECT w.id FROM workloads w JOIN audio_spans s ON s.upid=w.upid AND s.ts<w.ts+w.dur
 AND (s.dur<0 OR s.ts+s.dur>w.ts)
 WHERE s.dur<0 OR (s.name='StemWise.audio.retained_body_copy' AND (s.ts<w.ts OR s.ts+s.dur>w.ts+w.dur))
) bad ON bad.workload_id=w.id GROUP BY w.id
ORDER BY id, metric;
