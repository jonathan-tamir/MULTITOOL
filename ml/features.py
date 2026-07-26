import numpy as np
import soundfile as sf

SR=16000; WIN_SEC=1.0; N_FFT=512; HOP=256; N_MELS=64; FMIN=50.0; FMAX=8000.0

def _hz_to_mel(f): return 2595.0*np.log10(1.0+f/700.0)
def _mel_to_hz(m): return 700.0*(10.0**(m/2595.0)-1.0)

def mel_filterbank(sr=SR,n_fft=N_FFT,n_mels=N_MELS,fmin=FMIN,fmax=FMAX):
    mels=np.linspace(_hz_to_mel(fmin),_hz_to_mel(fmax),n_mels+2); hz=_mel_to_hz(mels)
    bins=np.floor((n_fft+1)*hz/sr).astype(int)
    fb=np.zeros((n_mels,n_fft//2+1),dtype=np.float32)
    for m in range(1,n_mels+1):
        l,c,r=bins[m-1],bins[m],bins[m+1]
        if c==l:c+=1
        if r==c:r+=1
        for k in range(l,c):
            if 0<=k<fb.shape[1]: fb[m-1,k]=(k-l)/max(c-l,1)
        for k in range(c,r):
            if 0<=k<fb.shape[1]: fb[m-1,k]=(r-k)/max(r-c,1)
    return fb
_FB=mel_filterbank()

def load_audio(path,sr=SR):
    x,fs=sf.read(path,dtype='float32')
    if x.ndim>1: x=x.mean(axis=1)
    if fs!=sr:
        n=int(round(len(x)*sr/fs))
        x=np.interp(np.linspace(0,len(x),n,endpoint=False),np.arange(len(x)),x).astype(np.float32)
    return x

def fix_length(x,sr=SR,win=WIN_SEC):
    n=int(sr*win)
    return x[:n] if len(x)>=n else np.pad(x,(0,n-len(x)))

def _stft_power(x):
    x=fix_length(x)
    w=np.hanning(N_FFT).astype(np.float32)
    starts=range(0,len(x)-N_FFT+1,HOP)
    frames=np.stack([x[s:s+N_FFT]*w for s in starts])
    spec=np.fft.rfft(frames,axis=1)
    return (spec.real**2+spec.imag**2).astype(np.float32)

def logmel(x):
    P=_stft_power(x); mel=P@_FB.T
    return np.log(mel+1e-6).T.astype(np.float32)

def pooled_features(x):
    M=logmel(x)
    return np.concatenate([M.mean(1),M.std(1),M.max(1)]).astype(np.float32)

def features_from_file(path): return pooled_features(load_audio(path))

def windows_from_file(path,hop_sec=0.5):
    x=load_audio(path); n=int(SR*WIN_SEC); step=int(SR*hop_sec)
    if len(x)<n:
        yield 0.0,fix_length(x); return
    for s in range(0,len(x)-n+1,step): yield s/SR,x[s:s+n]
