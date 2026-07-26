import sys, os, json
sys.path.insert(0, os.path.dirname(__file__))
import numpy as np
from concurrent.futures import ProcessPoolExecutor
from sklearn.model_selection import GroupShuffleSplit
from sklearn.preprocessing import StandardScaler
from sklearn.neural_network import MLPClassifier
from sklearn.pipeline import Pipeline
from sklearn.metrics import precision_recall_curve, average_precision_score, roc_auc_score, confusion_matrix
import features as F, dataset as D

ROOT=os.environ['ROOT']; OUT=os.environ['OUT']; TARGET=0.95
def ext(p):
    try: return F.features_from_file(p)
    except: return None

files,labels,groups = D.build_index(ROOT, neg_cap=6000, seed=0)
labels=np.array(labels); groups=np.array(groups)
with ProcessPoolExecutor(max_workers=8) as ex:
    feats=list(ex.map(ext, files, chunksize=32))
keep=[i for i,f in enumerate(feats) if f is not None]
X=np.stack([feats[i] for i in keep]); y=labels[keep]; g=groups[keep]
tr,te=next(GroupShuffleSplit(1,test_size=0.25,random_state=0).split(X,y,g))
assert len(set(g[tr])&set(g[te]))==0

pipe=Pipeline([('sc',StandardScaler()),
               ('mlp',MLPClassifier(hidden_layer_sizes=(64,), alpha=1e-3,
                    max_iter=400, random_state=0, early_stopping=True))])
posidx=tr[y[tr]==1]; negidx=tr[y[tr]==0]
reps=max(1,len(negidx)//max(len(posidx),1))
tr_bal=np.concatenate([negidx]+[posidx]*reps)
pipe.fit(X[tr_bal], y[tr_bal])

p=pipe.predict_proba(X[te])[:,1]
ap=average_precision_score(y[te],p); roc=roc_auc_score(y[te],p)
prec,rec,thr=precision_recall_curve(y[te],p)
ok=np.where(rec[:-1]>=TARGET)[0]; ti=ok[-1] if len(ok) else int(np.argmax(rec[:-1]))
T=float(thr[ti]); P=float(prec[ti]); R=float(rec[ti])
tn,fp,fn,tp=confusion_matrix(y[te],(p>=T)).ravel()
metrics=dict(pr_auc=round(ap,4),roc_auc=round(roc,4),threshold=round(T,4),
      precision=round(P,4),recall=round(R,4),fpr=round(fp/max(fp+tn,1),4),
      tp=int(tp),fp=int(fp),fn=int(fn),tn=int(tn))
print('METRICS', json.dumps(metrics))
sc=pipe.named_steps['sc']; mlp=pipe.named_steps['mlp']
model=dict(sr=F.SR,n_fft=F.N_FFT,hop=F.HOP,n_mels=F.N_MELS,fmin=F.FMIN,fmax=F.FMAX,
  win_sec=F.WIN_SEC,feat='pooled_logmel_mean_std_max_192',
  scaler_mean=sc.mean_.tolist(),scaler_scale=sc.scale_.tolist(),
  W1=mlp.coefs_[0].T.tolist(),b1=mlp.intercepts_[0].tolist(),
  W2=mlp.coefs_[1].T.tolist(),b2=mlp.intercepts_[1].tolist(),
  activation='relu',threshold=T)
json.dump(model, open(OUT+'/model.json','w'))
json.dump(metrics, open(OUT+'/ondevice_metrics.json','w'), indent=2)
print('saved model.json', os.path.getsize(OUT+'/model.json'),'bytes | W1',
      len(model['W1']),'x',len(model['W1'][0]))
