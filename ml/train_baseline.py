"""Train a recall-tuned baseline detector on pooled log-mel features (sklearn).

This is the fast, lightweight proof of the whole pipeline. It needs no deep
learning framework and trains in seconds. The per-node detector is deliberately
tuned RECALL-FIRST: in the multi-phone system, cross-node consensus supplies the
precision, so a single phone should err toward "flag it" rather than miss.

Usage:
    python src/train_baseline.py --data path/to/DroneAudioDataset --target-recall 0.95
"""
import sys, os, json, time, argparse
sys.path.insert(0, os.path.dirname(__file__))
import numpy as np
from concurrent.futures import ProcessPoolExecutor
from sklearn.model_selection import GroupShuffleSplit
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import (precision_recall_curve, average_precision_score,
                             roc_auc_score, confusion_matrix)
import joblib
import features as F
import dataset as D


def extract(path):
    try:
        return F.features_from_file(path)
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', default=D.DEFAULT_ROOT)
    ap.add_argument('--out', default=os.path.dirname(os.path.dirname(__file__)))
    ap.add_argument('--neg-cap', type=int, default=4500)
    ap.add_argument('--target-recall', type=float, default=0.95)
    ap.add_argument('--workers', type=int, default=8)
    a = ap.parse_args()

    t0 = time.time()
    files, labels, groups = D.build_index(a.data, neg_cap=a.neg_cap, seed=0)
    labels = np.array(labels); groups = np.array(groups)
    print(f'files={len(files)} pos={int(labels.sum())} neg={int((labels==0).sum())}')

    with ProcessPoolExecutor(max_workers=a.workers) as ex:
        feats = list(ex.map(extract, files, chunksize=32))
    keep = [i for i, f in enumerate(feats) if f is not None]
    X = np.stack([feats[i] for i in keep]); y = labels[keep]; g = groups[keep]
    print(f'features X={X.shape} in {time.time()-t0:.1f}s')

    # group-held-out split: no source recording appears in both train and test
    gss = GroupShuffleSplit(n_splits=1, test_size=0.25, random_state=0)
    tr, te = next(gss.split(X, y, g))
    Xtr, Xte, ytr, yte = X[tr], X[te], y[tr], y[te]
    assert len(set(g[tr]) & set(g[te])) == 0, 'group leakage!'
    print(f'train={len(tr)} test={len(te)} | test pos={int(yte.sum())} neg={int((yte==0).sum())}')

    # class imbalance -> balanced sample weights
    w = np.where(ytr == 1, (ytr == 0).sum() / max((ytr == 1).sum(), 1), 1.0)
    clf = HistGradientBoostingClassifier(max_iter=300, learning_rate=0.08,
                                         l2_regularization=1.0,
                                         validation_fraction=0.15, random_state=0)
    clf.fit(Xtr, ytr, sample_weight=w)

    p = clf.predict_proba(Xte)[:, 1]
    ap_ = average_precision_score(yte, p)
    roc = roc_auc_score(yte, p)
    prec, rec, thr = precision_recall_curve(yte, p)
    ok = np.where(rec[:-1] >= a.target_recall)[0]
    ti = ok[-1] if len(ok) else int(np.argmax(rec[:-1]))
    T, P, R = float(thr[ti]), float(prec[ti]), float(rec[ti])
    yhat = (p >= T).astype(int)
    tn, fp, fn, tp = confusion_matrix(yte, yhat).ravel()

    metrics = dict(pr_auc=round(ap_, 4), roc_auc=round(roc, 4),
                   threshold=round(T, 4), precision_at_target=round(P, 4),
                   recall_at_target=round(R, 4), target_recall=a.target_recall,
                   tp=int(tp), fp=int(fp), fn=int(fn), tn=int(tn),
                   false_positive_rate=round(fp / max(fp + tn, 1), 4),
                   n_train=len(tr), n_test=len(te))
    os.makedirs(f'{a.out}/models', exist_ok=True)
    os.makedirs(f'{a.out}/results', exist_ok=True)
    joblib.dump({'model': clf, 'threshold': T, 'feat': 'pooled_logmel_192'},
                f'{a.out}/models/baseline.joblib')
    json.dump(metrics, open(f'{a.out}/results/baseline_metrics.json', 'w'), indent=2)
    print('METRICS', json.dumps(metrics))
    print(f'saved models/baseline.joblib | done in {time.time()-t0:.1f}s')


if __name__ == '__main__':
    main()
