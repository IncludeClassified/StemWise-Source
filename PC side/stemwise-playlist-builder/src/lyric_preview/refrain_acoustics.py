"""Compare repeated vocal articulation with the solo and instrumental controls."""
import numpy as np
from scipy import ndimage, signal


def articulation(samples):
    frequencies,_,spectrum=signal.stft(samples,16000,nperseg=1024,noverlap=704)
    magnitude=abs(spectrum)
    edges=np.geomspace(130,7000,33)
    bands=np.stack([np.sqrt(np.mean(magnitude[(frequencies>=a)&(frequencies<b)]**2,axis=0))
                    for a,b in zip(edges,edges[1:])])
    features=np.log1p(3000*bands)
    features-=ndimage.gaussian_filter1d(features,12,axis=1)
    features/=np.maximum(1.,np.linalg.norm(features,axis=0,keepdims=True))
    return features


def recurrence(samples,starts,duration):
    features=articulation(samples)
    length=round(duration/20)
    starts=[round(t/20) for t in starts]
    result=np.zeros((len(starts),len(starts)))
    for i,left in enumerate(starts):
        template=features[:,left:left+length]
        for j,right in enumerate(starts):
            other=features[:,max(0,right-50):right+length+50]
            if template.shape[1]==length and other.shape[1]>=length:
                corr=sum(signal.correlate(y,x,'valid',method='fft') for x,y in zip(template,other))
                result[i,j]=corr.max()/length
    return np.minimum(result,result.T)


def corresponding_windows(features, reference, target, duration, times, *, pitched):
    """Map search neighborhoods through the target vocals. Only the timer returns boundaries."""
    margin=1500
    a,b=(max(0,(v-margin)//20) for v in (reference,target))
    count=(duration+2*margin)//20
    x,y=features[:,a:a+count:2],features[:,b:b+count:2]
    length=min(x.shape[1],y.shape[1])
    if length<3:
        raise ValueError('Vocal template has no common extent')
    x,y=x[:,:length],y[:,:length]
    if pitched:
        shift=max(range(-6,7,2),key=lambda k:float((np.roll(x,k,axis=0)*y).sum()))
        x=np.roll(x,shift,axis=0)
    metric=np.maximum(0.,1.-x.T@y)
    cost=np.full((length+2,length+2),np.inf)
    parent=np.zeros((length,length),dtype=np.int8)
    cost[1,1]=0
    for i,row in enumerate(metric):
        options=np.stack([cost[i+1,1:-1]+row,cost[i+1,:-2]+2*row,cost[i,1:-1]+2*row])
        parent[i]=options.argmin(axis=0)
        cost[i+2,2:]=options.min(axis=0)
    i=j=length-1
    pairs=[]
    while i>=0 and j>=0:
        pairs.append((i,j))
        di,dj=((1,1),(1,2),(2,1))[parent[i,j]]
        i,j=i-di,j-dj
    ix,iy=np.array(pairs[::-1]).T
    return np.interp(np.asarray(times),a*20+ix*40,b*20+iy*40).round().astype(int)


def concurrent_regions(scores,similarity,controls,count):
    """Require a shared vocal pattern unique to the second part across its renditions."""
    wide,center,backing=(similarity[k] for k in ('wide','center','accompaniment'))
    solo_contrast=max(0.,*(row[0]-row[1] for row in scores['wide'][:controls]))
    neighbors={}
    for i in range(controls,len(wide)):
        w,c=scores['wide'][i],scores['center'][i]
        if c[2]<=max(c[0],c[1],c[3]):
            return []
        if (w[0]<=max(w[3],c[0]) or w[0]-w[1]<=max(solo_contrast,c[0]-c[1])
                or c[2]-c[0]<=w[2]-w[0]):
            continue
        neighbors[i]=set()
        for j in neighbors:
            if i!=j and wide[i,j]>max(center[i,j],backing[i,j],*wide[i,:controls],*wide[j,:controls]):
                neighbors[i].add(j)
                neighbors[j].add(i)
    chosen=[i for i,others in neighbors.items() if others]
    if len(chosen)!=count or any(len(neighbors[i])!=count-1 for i in chosen):
        return []
    return [i-controls for i in chosen]
