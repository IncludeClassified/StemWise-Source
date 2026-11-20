"""Prepare full, unedited pinned CC BY-ND recordings for private timing evaluation."""
import csv,hashlib,json,sys
from decimal import Decimal,ROUND_HALF_UP
from pathlib import Path
import numpy as np
import soundfile as sf
from stemwise_engine.common import atomic_write_bytes,sha256_file,run_process_tree
from stemwise_engine.youtube import decode_analysis_audio
from stemwise_engine.lyrics_tokenization import display_words
from stemwise_engine.lyrics_normalize import physical_lines
root=Path(__file__).resolve().parent
source=root/'source'
project=Path(sys.argv[1]).resolve()
prior=project/'Test suite/fixtures/lyrics-timing/English/timing-reference.json'
prior_sha='b307de76994d46604e780c27707d71cb1f1665a9a4d2a029f39ba23f041f9bc4'
revision='de188c963fd4539bc769b3feb83582e5a9595e36'
pins=[
 ('hila-give-me-the-same','HILA_-_Give_Me_the_Same',322,'ebef56766822631b51bada37e403ddcf9ef08d9a902e74b5ec635f7589537c1c','0c9962ef612c8f33c5fd0ad9ac5250f9a6437a4df495c45598a9f3e5f76af859','f447cec1cf8d90f765172088f48cd988d53011d0a478358ee22a6360fa497158','9c64200d7c101164cdb6832a1b45fab239088bc2da9311cbae382e797b76b880'),
 ('songwriterz-back-in-time','Songwriterz_-_Back_In_Time',238,'bcfeb4d28b618b7b25c2567b0465dee01b3bd4d2e4034dcacdb88804162d3f87','701ff31f0cd3d2c433fc68ecc12661f39c058c77656834b15c5f76e496027dde','8c258721320003ad7fb30391ac326e2165120ecc3060f8e1c0a1ff26b800dc9f','93fefbb011cf14eeccccf1fe1e91444481a456d247c07acebdd2985d9cfb1d0e'),
 ('kinematic-peyote','Kinematic_-_Peyote',147,'9a675e89bb3b4882a38c0571475eff6917db4bc1d96ec19e3e910995741ffe99','54a5e1c674632c936bbf43bbf1ae2058d1a00b19256aae5d6e2e7727a503dae7','f05c70857132cca9a4977ee829926cee9263d9599a4d91887ec81d658a994fc6','c67066b86e2362032c7e33ae3b775355ca54a5e79f9a7f8f88c647129bce6b5a'),
 ('wordsmith-the-statement','Wordsmith_-_The_Statement',581,'c7da05309e6eb613601b427a019cd54d157df7ce93bea0924bdef906b5d99cee','54331e5500a487aa44d2a5479f565af014db01576d95322c006301a73a10fff6','fe59045f2e23b9459705656e01d6fee276ec20ff4c8c75e732f77ff47aecdd3a','80afb4b50a692914cbf72870b97216a24346feba15ce3d9ed34fe3cb971f7fa1'),
 ('ridgway-fire-inside','Ridgway_-_Fire_Inside',304,'9d51450a081eb62b1d5149ff5199eb5912ba258e51709a459914da121b3dab3e','11c77799a7bde6b96e12be9f9c233d912437a6fd2f16541e031c056415c4db0b','7404f250cde17f68e607838747e66687f60077f79562f26dfcafcc4ad437a0d8','6233a162b5c5bf5c9878bc77ad4ebe1bb252484e022c5d10e62d1296f7752cf1'),
 ('tom-orlando-the-one','Tom_Orlando_-_The_One__feat._Tina_G_',498,'b5bbad56cf1f40dc36556b1f6bfa414a6c5fba6bd48058486c7600c25fb01a7d','b4183f0a04d29a30922cfdcbd4de5d9acaa8d45e51484bcaa9bd14c0a810630b','5a90168fdf7038557b26a3ee4115f6b9d0c3dabbba9fd8d50e86c31be8ac53a7','082f082f9890aeefc9114ed502079472e114d601ab3e32ebe5c67a153bdd986e')]
def write(path,raw):
 if path.exists():
  if path.read_bytes()!=raw:raise ValueError('Refuse replacing differing input: '+str(path))
 else:
  path.parent.mkdir(parents=True,exist_ok=True);atomic_write_bytes(path,raw)
def ms(value):return int((value*1000).quantize(Decimal(1),rounding=ROUND_HALF_UP))
def bytes_json(value):return (json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n').encode('utf-8')
if sha256_file(prior)!=prior_sha:raise ValueError('Prior reference changed')
prior_doc=json.loads(prior.read_text(encoding='utf-8'))
for case in prior_doc['musical']:
 if sha256_file(prior.parent/case['audio'])!=case['audioSha256']:raise ValueError('Prior development audio changed')
if sha256_file(source/'JamendoLyrics.csv')!='a4649131242fa5f0750df547a5ceaaaa7c04329c249dd5287799e6d8ac69ed68':raise ValueError('Dataset metadata changed')
metadata={Path(x['Filepath']).stem:x for x in csv.DictReader((source/'JamendoLyrics.csv').read_text(encoding='utf-8').splitlines())}
collection={'kind':'stemwise.timing-reference-collection','formatVersion':1,'scope':'Predeclared complete independently annotated English recordings for private Sylla timing qualification; inputs only, no qualification or content approval','dataset':'jamendolyrics/jamendolyrics','revision':revision,'predeclaredSplit':'held-out','trainingOverlapStatus':'Unknown; held-out means unseen in this local qualification only','developmentReference':{'path':str(prior.relative_to(project)),'sha256':prior_sha,'caseIds':[x['id'] for x in prior_doc['musical']],'splitForThisQualification':'development'},'annotationUncertaintyMs':None,'annotationEndSemantics':'Raw human word_end seconds; phonetic offset versus reverb-tail convention unspecified','audioUse':'Private complete-recording technical format conversion only; no crop, amplitude edit, time edit, or audio redistribution','musical':[]}
for case_id,name,count,audio_sha,ann_sha,word_sha,text_sha in pins:
 file_id=case_id+'-full'
 audio=source/'subsets/en/mp3'/f'{name}.mp3';ann=source/'annotations/words'/f'{name}.csv';wordfile=source/'lyrics'/f'{name}.words.txt';textfile=source/'lyrics'/f'{name}.txt'
 for path,expected in ((audio,audio_sha),(ann,ann_sha),(wordfile,word_sha),(textfile,text_sha)):
  if sha256_file(path)!=expected:raise ValueError('Source hash changed: '+str(path))
 meta=metadata[name]
 if meta['LicenseType']!='BY-ND' or meta['Language']!='English':raise ValueError('Selected metadata differs')
 probe=json.loads(run_process_tree(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(audio)],capture_output=True,text=True,check=True,timeout=30).stdout)
 tags=probe['format']['tags'];license_url=tags['copyright'].replace('http://','https://')
 if license_url!='https://creativecommons.org/licenses/by-nd/3.0/':raise ValueError('Actual license differs: '+name+' '+license_url)
 rows=list(csv.DictReader(ann.read_text(encoding='utf-8').splitlines()));words=wordfile.read_text(encoding='utf-8').splitlines();text_raw=textfile.read_bytes();text=text_raw.decode('utf-8')
 if len(rows)!=count or len(words)!=count:raise ValueError('Predeclared complete word count differs')
 parsed=[(Decimal(r['word_start']),Decimal(r['word_end'])) for r in rows]
 if any(not a.is_finite() or not b.is_finite() for a,b in parsed):raise ValueError('Nonfinite human labels require explicit unresolved handling')
 decoded_dir=root/'decoded'/name;decoded=decode_analysis_audio(audio,decoded_dir,timeout_seconds=120)
 output=root/'audio'/f'{file_id}.wav';output.parent.mkdir(exist_ok=True)
 pending=root/(file_id+'.pending.wav')
 run_process_tree(['ffmpeg','-v','error','-nostdin','-y','-i',str(decoded),'-map','0:a:0','-map_metadata','-1','-c:a','pcm_f32le','-fflags','+bitexact','-flags:a','+bitexact','-write_peak','0',str(pending)],capture_output=True,check=True,timeout=60)
 write(output,pending.read_bytes());pending.unlink()
 pcm_hash=hashlib.sha256();overshoot=0
 with sf.SoundFile(decoded) as before,sf.SoundFile(output) as after:
  if before.samplerate!=48000 or after.samplerate!=48000 or before.channels!=2 or after.channels!=2 or len(before)!=len(after):raise ValueError('Technical conversion changed geometry')
  frames=len(after)
  for block in before.blocks(blocksize=480000,dtype='float32',always_2d=True):
   actual=after.read(len(block),dtype='float32',always_2d=True)
   if not np.array_equal(block,actual):raise ValueError('Technical conversion changed decoded samples')
   pcm_hash.update(actual.astype('<f4').tobytes());overshoot+=int(np.count_nonzero(np.abs(actual)>=1))
 duration=Decimal(frames)/48000
 issues=[];projection_notes=[];lines=[];cursor=0;display_line_ends=[]
 for physical,line in enumerate(physical_lines(text)):
  if not line.strip():continue
  spans=display_words(line);mapped=[]
  for left,right,surface in spans:
   if cursor>=count:raise ValueError('Original lyrics have extra lexical words')
   annotation_surface=display_words(words[cursor])
   if len(annotation_surface)!=1 or annotation_surface[0][2]!=surface:raise ValueError('Unedited lyric word ownership differs at '+name+':'+str(cursor))
   if words[cursor]!=surface:projection_notes.append({'sourceWordIndex':cursor,'annotationWordText':words[cursor],'displayWordText':surface,'reason':'Existing frozen display tokenizer excludes boundary punctuation; source bytes unchanged'})
   a,b=parsed[cursor];start_ms,end_ms=ms(a),ms(b)
   if not 0<=a<b<=duration:issues.append({'kind':'invalid-raw-boundary','sourceWordIndex':cursor,'startSeconds':str(a),'endSeconds':str(b)})
   if not 0<=start_ms<end_ms<=ms(duration):issues.append({'kind':'invalid-ms-projection','sourceWordIndex':cursor,'startMs':start_ms,'endMs':end_ms})
   if cursor and a<parsed[cursor-1][1]:issues.append({'kind':'overlap','sourceWordIndex':cursor,'previousWordIndex':cursor-1,'previousEndSeconds':str(parsed[cursor-1][1]),'startSeconds':str(a),'overlapSeconds':str(parsed[cursor-1][1]-a)})
   mapped.append({'text':surface,'annotationWordText':words[cursor],'sourceWordIndex':cursor,'sourceStartSeconds':rows[cursor]['word_start'],'sourceEndSeconds':rows[cursor]['word_end'],'startMs':start_ms,'endMs':end_ms,'startUtf16':left,'endUtf16':right});cursor+=1
  display_line_ends.append(cursor-1);lines.append({'text':line,'sourcePhysicalIndex':physical,'words':mapped})
 if cursor!=count:raise ValueError('Original lyrics omit annotated words')
 csv_line_ends=[i for i,r in enumerate(rows) if r['line_end'].lower()!='nan']
 break_differences=sorted(set(csv_line_ends)^set(display_line_ends))
 lyrics=root/'inputs'/f'{file_id}.lyrics.txt';write(lyrics,text_raw)
 first_words=[line['words'][0] for line in lines if line['words']]
 long_initial=sorted([{'sourceWordIndex':w['sourceWordIndex'],'durationSeconds':str(Decimal(w['sourceEndSeconds'])-Decimal(w['sourceStartSeconds']))} for w in first_words],key=lambda w:Decimal(w['durationSeconds']),reverse=True)[:3]
 gaps=sorted([{'previousWordIndex':i-1,'sourceWordIndex':i,'gapSeconds':str(parsed[i][0]-parsed[i-1][1])} for i in range(1,count) if parsed[i][0]>parsed[i-1][1]],key=lambda x:Decimal(x['gapSeconds']),reverse=True)[:3]
 repeats=[i for i in range(1,count) if words[i]==words[i-1]]
 case={'id':case_id,'language':'en','split':'held-out','heldOutByUs':True,'wholeRecording':True,'audio':output.relative_to(root).as_posix(),'audioSha256':sha256_file(output),'decodedF32leSha256':pcm_hash.hexdigest(),'sampleRate':48000,'channels':2,'frames':frames,'durationMs':ms(duration),'modelInputLyrics':lyrics.relative_to(root).as_posix(),'modelInputLyricsSha256':text_sha,'sourceWordRange':[0,count],'wordCount':count,'lines':lines,'referenceIssues':issues,'annotationDisplayProjectionNotes':projection_notes,'publishedLineBreakDifferences':break_differences,'labelStrata':{'annotatedFirstOnsetSeconds':rows[0]['word_start'],'longestLineInitialWords':long_initial,'largestAnnotatedGaps':gaps,'consecutiveRepeatedWordIndices':repeats,'datasetFlags':{k:meta[k] for k in ('LyricOverlap','Polyphonic','NonLexical')}},'provenance':{'dataset':'jamendolyrics/jamendolyrics','revision':revision,'datasetUrl':f'https://huggingface.co/datasets/jamendolyrics/jamendolyrics/tree/{revision}','sourceAudio':audio.relative_to(root).as_posix(),'sourceAudioSha256':audio_sha,'sourceAudioBytes':audio.stat().st_size,'sourceAudioSampleRate':int(probe['streams'][0]['sample_rate']),'sourceAudioContainerStartSeconds':probe['streams'][0]['start_time'],'wordAnnotations':ann.relative_to(root).as_posix(),'wordAnnotationsSha256':ann_sha,'wordText':wordfile.relative_to(root).as_posix(),'wordTextSha256':word_sha,'originalLyrics':textfile.relative_to(root).as_posix(),'originalLyricsSha256':text_sha,'artist':meta['Artist'],'title':meta['Title'],'actualAudioTags':tags,'trackUrl':meta['URL'],'audioAndLyricsLicense':license_url,'licenseLegalcode':'https://creativecommons.org/licenses/by-nd/3.0/legalcode.en','annotationsLicense':'MIT; source/LICENSE','annotationUncertaintyMs':None,'annotationEndSemantics':'Published raw human word_end; phonetic/reverb-tail convention unknown','technicalConversion':'Existing complete gapless canonical48kHz stereo FLOAT decode and deterministic bitexact pcm_f32le WAV; no crop/scale/clip/retime','preservedOvershootSampleCount':overshoot}}
 collection['musical'].append(case)
 print(json.dumps({'id':case_id,'words':count,'durationMs':case['durationMs'],'audioSha256':case['audioSha256'],'rawAudioSha256':audio_sha,'annotationSha256':ann_sha,'license':license_url,'issues':len(issues),'displayProjectionNotes':len(projection_notes),'lineBreakDifferences':len(break_differences)},ensure_ascii=False),flush=True)
 decoded.unlink();decoded_dir.rmdir()
if len({x['provenance']['artist'] for x in collection['musical']})!=6:raise ValueError('Artists not distinct')
if sha256_file(prior)!=prior_sha:raise ValueError('Original development reference changed')
collection['totalWords']=sum(x['wordCount'] for x in collection['musical']);collection['totalDurationMs']=sum(x['durationMs'] for x in collection['musical'])
write(root/'input-manifest.json',bytes_json(collection))
print(json.dumps({'manifestSha256':sha256_file(root/'input-manifest.json'),'totalWords':collection['totalWords'],'totalDurationMs':collection['totalDurationMs'],'modelsRun':False},ensure_ascii=False))
