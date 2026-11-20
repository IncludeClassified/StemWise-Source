"""Prepare pinned independent English singing references; never run models."""
import argparse,csv,hashlib,json
from decimal import Decimal,ROUND_HALF_UP
from pathlib import Path
import numpy as np
import soundfile as sf
from stemwise_engine.common import sha256_file,run_process_tree,atomic_write_bytes
from stemwise_engine.youtube import decode_analysis_audio
from stemwise_engine.lyrics_tokenization import display_words
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source',required=True,type=Path)
args=parser.parse_args()
source=args.source.resolve()
work=source.parent
target=Path(__file__).resolve().parent
revision='de188c963fd4539bc769b3feb83582e5a9595e36'
pins={
 'Cortez_-_Feel__Stripped_':('beefa48e4520bb290e739faa7c0a91e4e7296fb239577966b2129934ccf86b31','5f8f43dd1443b0fe53962491e9a268f13ee3a1b277a9e92d4b962c3a2d9863e4','558091d2eff3db13e0167a9e72774c627cbc20ac80419a54b31536d9a64192ec','by'),
 'Rxbyn_-_Bad_Side':('24090af9ca4886bb0551f7c1896c249af3311cce1c585092054ed5c428834d44','040bb6c15c870b2a0ce39ee8f302bf74848ec4788305daf71b5094d1a3deb3b5','1a03b21b3ab66050987de7c68cc86cd597f03d5f4785d3fe13774218d6182d33','by'),
 'Lower_Loveday_-_Is_It_Right_':('84e344829620711e39f9c189f15671f7ffd184946cf03e269c23c6061c05e1eb','7f1f7744622e7dad945be9f501ce2638cfbb79cbd579e46a1dbe3e98ca5998c9','b4813ae07f52d387cb581038369c31711337004c07dcc1438c5e93738e76c75b','by-sa')}
cases={
 'Cortez_-_Feel__Stripped_':[('cortez-intro-held-01','0','39.5',0,28,False),('cortez-gap-repeat-02','94.5','134',130,183,False)],
 'Lower_Loveday_-_Is_It_Right_':[('lower-loveday-heldout-01','0','46.7',0,24,True)],
 'Rxbyn_-_Bad_Side':[('rxbyn-heldout-01','0','40',0,53,True)]}
metadata={Path(row['Filepath']).stem:row for row in csv.DictReader((source/'JamendoLyrics.csv').read_text(encoding='utf-8').splitlines())}
def write(path,raw):
 if path.exists():
  if path.read_bytes()!=raw:raise ValueError('Refuse replacing different fixture: '+str(path))
 else:
  path.parent.mkdir(parents=True,exist_ok=True);atomic_write_bytes(path,raw)
def encoded(value):return (json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
def ms(value):return int((value*1000).quantize(Decimal(1),rounding=ROUND_HALF_UP))
collection={'kind':'stemwise.timing-reference-collection','formatVersion':1,'scope':'Independent published human word boundaries for private timing evaluation; no candidate, content, or model qualification','trainingOverlapStatus':'Unknown for the installed models; heldOutByUs only records local evaluation partition','musical':[]}
attribution=[]
for name,group in cases.items():
 audio=source/'subsets/en/mp3'/f'{name}.mp3';annotation=source/'annotations/words'/f'{name}.csv';wordtext=source/'lyrics'/f'{name}.words.txt'
 audio_sha,annotation_sha,word_sha,license_name=pins[name]
 for path,digest in ((audio,audio_sha),(annotation,annotation_sha),(wordtext,word_sha)):
  if sha256_file(path)!=digest:raise ValueError('Pinned source changed: '+str(path))
 record=metadata[name]
 if record['LicenseType'].lower()!=license_name:raise ValueError('Dataset license differs')
 probe=json.loads(run_process_tree(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(audio)],capture_output=True,text=True,check=True,timeout=30).stdout)
 license_url=probe['format']['tags']['copyright'].replace('http://','https://')
 if license_url!=f'https://creativecommons.org/licenses/{license_name}/3.0/':raise ValueError('Actual copyright license differs')
 raw_annotation=annotation.read_bytes();rows=list(csv.DictReader(raw_annotation.decode('utf-8').splitlines()));words=wordtext.read_text(encoding='utf-8').splitlines()
 if len(rows)!=len(words):raise ValueError('Word/annotation count differs')
 parsed=[(Decimal(row['word_start']),Decimal(row['word_end'])) for row in rows]
 if any(not a.is_finite() or not b.is_finite() or a<0 or b<=a for a,b in parsed):raise ValueError('Invalid published boundaries')
 write(target/'sources'/f'{name}.csv',raw_annotation)
 write(target/'sources'/f'{name}.words.txt',wordtext.read_bytes())
 entry={'artist':probe['format']['tags']['artist'],'title':probe['format']['tags']['title'],'trackUrl':record['URL'],'audioAndLyricsLicense':license_url,'sourceAudio':f'subsets/en/mp3/{name}.mp3','sourceAudioSha256':audio_sha,'sourceAudioBytes':audio.stat().st_size,'wordAnnotations':f'annotations/words/{name}.csv','wordAnnotationsSha256':annotation_sha,'wordText':f'lyrics/{name}.words.txt','wordTextSha256':word_sha,'dataset':'jamendolyrics/jamendolyrics','revision':revision}
 attribution.append(entry)
 decoded_dir=work/'decoded'/name
 decoded=decode_analysis_audio(audio,decoded_dir,timeout_seconds=120)
 with sf.SoundFile(decoded) as stream:
  if stream.samplerate!=48000 or stream.channels!=2:raise ValueError('Canonical decode geometry changed')
  full_frames=len(stream)
  for case_id,start_text,end_text,first,stop,heldout in group:
   start,end=Decimal(start_text),Decimal(end_text)
   start_frame=int(start*48000);end_frame=int(end*48000)
   if start_frame!=start*48000 or end_frame!=end*48000 or end_frame>full_frames:raise ValueError('Source cut not an exact available frame')
   selected=[i for i,(a,b) in enumerate(parsed) if a<end and b>start]
   if selected!=list(range(first,stop)):raise ValueError('Predeclared selection differs')
   if any(parsed[i][0]<parsed[i-1][1] for i in selected[1:]):raise ValueError('Selected published boundaries overlap')
   if any(not start<=parsed[i][0]<parsed[i][1]<=end for i in selected):raise ValueError('Cut crosses an annotated word')
   if first and rows[first-1]['line_end'].lower()=='nan' or rows[stop-1]['line_end'].lower()=='nan':raise ValueError('Cut crosses a source line')
   stream.seek(start_frame);pcm=stream.read(end_frame-start_frame,dtype='float32',always_2d=True)
   if len(pcm)!=end_frame-start_frame:raise ValueError('Short decoded crop')
   pending=work/(case_id+'.wav')
   run_process_tree(['ffmpeg','-v','error','-nostdin','-y','-f','f32le','-ar','48000','-ac','2','-i','pipe:0',
       '-map_metadata','-1','-c:a','pcm_f32le','-fflags','+bitexact','-flags:a','+bitexact',
       '-write_peak','0',str(pending)],input=pcm.astype('<f4').tobytes(),capture_output=True,check=True,timeout=60)
   encoded_audio=pending.read_bytes();write(target/(case_id+'.wav'),encoded_audio);pending.unlink()
   reread,rate=sf.read(target/(case_id+'.wav'),dtype='float32',always_2d=True)
   if rate!=48000 or reread.shape!=pcm.shape:raise ValueError('Fixture geometry differs')
   max_error=float(np.max(np.abs(pcm-reread)))
   if max_error!=0:raise ValueError('Float WAV did not preserve exact decoded PCM')
   lines=[];current=[];source_line=sum(row['line_end'].lower()!='nan' for row in rows[:first])
   for i in selected:
    current.append(i)
    if rows[i]['line_end'].lower()!='nan':
     text=' '.join(words[j] for j in current)
     spans=display_words(text)
     if [surface for _,_,surface in spans]!=[words[j] for j in current]:raise ValueError('Published words differ from display words')
     line={'text':text,'sourceLineIndex':source_line,'words':[]}
     for j,(left,right,surface) in zip(current,spans):
      a,b=parsed[j]
      line['words'].append({'text':surface,'sourceWordIndex':j,'sourceStartSeconds':rows[j]['word_start'],'sourceEndSeconds':rows[j]['word_end'],'startMs':ms(a-start),'endMs':ms(b-start),'startUtf16':left,'endUtf16':right})
     lines.append(line);source_line+=1;current=[]
   if current:raise ValueError('Incomplete source line')
   lyrics='\n'.join(line['text'] for line in lines)+'\n';write(target/(case_id+'.lyrics.txt'),lyrics.encode('utf-8'))
   fixture={'id':case_id,'language':'en','audio':case_id+'.wav','audioSha256':hashlib.sha256(encoded_audio).hexdigest(),'decodedF32leSha256':hashlib.sha256(reread.astype('<f4').tobytes()).hexdigest(),'sampleRate':48000,'channels':2,'frames':len(reread),'durationMs':ms(end-start),'heldOutByUs':heldout,'modelInputLyrics':case_id+'.lyrics.txt','modelInputLyricsSha256':hashlib.sha256(lyrics.encode('utf-8')).hexdigest(),'sourceWordRange':[first,stop],'predeclaredSourceSeconds':[start_text,end_text],'crop':{'sourceStartFrame':start_frame,'sourceEndFrameExclusive':end_frame,'sourceSampleRate':48000,'sourceDecodedFrames':full_frames,'adaptation':'Existing gapless canonical 48 kHz stereo decode, exact decoded-frame crop, FLOAT WAV; no amplitude normalization or timing correction','maxAbsoluteF32CodecError':max_error},'provenance':{**entry,'datasetUrl':f'https://huggingface.co/datasets/jamendolyrics/jamendolyrics/tree/{revision}','annotationMethod':'Published manual word annotations; no local reviewer approval','annotationUncertaintyMs':None,'uncertaintyReason':'Not supplied by dataset; no sample-accurate boundary claim','annotationEndSemantics':'Published word_end seconds; phonetic offset versus reverb-tail convention not supplied','sourceAudioSampleRate':int(probe['streams'][0]['sample_rate']),'sourceAudioContainerStartSeconds':probe['streams'][0]['start_time'],'clockPolicy':'Decoded audio zero; no manual subtraction of container start_time','wordAnnotationsLocal':'sources/'+name+'.csv','wordTextLocal':'sources/'+name+'.words.txt','annotationsLicense':'MIT; see LICENSE.annotations.txt'},'lines':lines}
   collection['musical'].append(fixture)
   print(json.dumps({'id':case_id,'words':sum(len(line['words']) for line in lines),'audioSha256':fixture['audioSha256'],'annotationSha256':annotation_sha,'sourceFrames':[start_frame,end_frame],'sampleRate':48000,'audioBytes':len(encoded_audio)},ensure_ascii=False),flush=True)
 decoded.unlink();decoded_dir.rmdir()
write(target/'LICENSE.annotations.txt',(source/'LICENSE').read_bytes())
write(target/'JamendoLyrics.csv',(source/'JamendoLyrics.csv').read_bytes())
write(target/'ATTRIBUTION.json',encoded(attribution))
write(target/'timing-reference.json',encoded(collection))
