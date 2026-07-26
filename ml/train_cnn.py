"""Recall-tuned CNN detector on log-mel spectrograms (PyTorch).

This is the upgrade over the sklearn baseline and the intended per-node model.
It was NOT trained in the prototyping sandbox (PyTorch wasn't installable there),
but it runs on any machine with torch:  pip install torch

    python src/train_cnn.py --data path/to/DroneAudioDataset --epochs 25

It reuses the SAME features.logmel() as everything else, so the model input is
identical to what the FPV scorer will feed it. Model is ~120k params -> small
enough to later export (ONNX / TFLite) and run in real time on your Android node.
"""
import sys, os, json, time, argparse
sys.path.insert(0, os.path.dirname(__file__))
import numpy as np
from concurrent.futures import ProcessPoolExecutor
from sklearn.model_selection import GroupShuffleSplit
from sklearn.metrics import precision_recall_curve, average_precision_score, confusion_matrix
import features as F
import dataset as D

import torch
import torch.nn as nn
from torch.utils.data import TensorDataset, DataLoader


class DroneCNN(nn.Module):
    """Small 2D CNN over a (1 x 64 x T) log-mel image."""
    def __init__(self, n_mels=F.N_MELS):
        super().__init__()
        self.net = nn.Sequential(
            nn.Conv2d(1, 16, 3, padding=1), nn.BatchNorm2d(16), nn.ReLU(), nn.MaxPool2d(2),
            nn.Conv2d(16, 32, 3, padding=1), nn.BatchNorm2d(32), nn.ReLU(), nn.MaxPool2d(2),
            nn.Conv2d(32, 64, 3, padding=1), nn.BatchNorm2d(64), nn.ReLU(),
            nn.AdaptiveAvgPool2d(1),
        )
        self.head = nn.Sequential(nn.Flatten(), nn.Dropout(0.3), nn.Linear(64, 1))

    def forward(self, x):
        return self.head(self.net(x)).squeeze(1)


def _logmel_img(path):
    try:
        M = F.logmel(F.load_audio(path))          # (64, T)
        # per-sample standardization stabilizes across mic gains / distances
        M = (M - M.mean()) / (M.std() + 1e-6)
        return M
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', default=D.DEFAULT_ROOT)
    ap.add_argument('--out', default=os.path.dirname(os.path.dirname(__file__)))
    ap.add_argument('--neg-cap', type=int, default=6000)
    ap.add_argument('--epochs', type=int, default=25)
    ap.add_argument('--batch', type=int, default=64)
    ap.add_argument('--target-recall', type=float, default=0.95)
    a = ap.parse_args()

    dev = 'cuda' if torch.cuda.is_available() else 'cpu'
    t0 = time.time()
    files, labels, groups = D.build_index(a.data, neg_cap=a.neg_cap, seed=0)
    labels = np.array(labels); groups = np.array(groups)

    with ProcessPoolExecutor() as ex:
        imgs = list(ex.map(_logmel_img, files, chunksize=32))
    keep = [i for i, m in enumerate(imgs) if m is not None]
    T = min(m.shape[1] for m in (imgs[i] for i in keep))
    X = np.stack([imgs[i][:, :T] for i in keep])[:, None, :, :].astype(np.float32)
    y = labels[keep].astype(np.float32); g = groups[keep]
    print(f'X={X.shape} pos={int(y.sum())} neg={int((y==0).sum())} in {time.time()-t0:.1f}s dev={dev}')

    tr, te = next(GroupShuffleSplit(1, test_size=0.25, random_state=0).split(X, y, g))
    assert len(set(g[tr]) & set(g[te])) == 0
    dl = DataLoader(TensorDataset(torch.tensor(X[tr]), torch.tensor(y[tr])),
                    batch_size=a.batch, shuffle=True)

    model = DroneCNN().to(dev)
    pos_w = torch.tensor([(y[tr] == 0).sum() / max((y[tr] == 1).sum(), 1)], device=dev)
    lossf = nn.BCEWithLogitsLoss(pos_weight=pos_w)   # recall-first: upweight the drone class
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)

    for ep in range(a.epochs):
        model.train(); tot = 0.0
        for xb, yb in dl:
            xb, yb = xb.to(dev), yb.to(dev)
            opt.zero_grad(); loss = lossf(model(xb), yb); loss.backward(); opt.step()
            tot += loss.item() * len(xb)
        print(f'epoch {ep+1}/{a.epochs} loss={tot/len(tr):.4f}')

    model.eval()
    with torch.no_grad():
        p = torch.sigmoid(model(torch.tensor(X[te]).to(dev))).cpu().numpy()
    yte = y[te]
    prec, rec, thr = precision_recall_curve(yte, p)
    ok = np.where(rec[:-1] >= a.target_recall)[0]
    ti = ok[-1] if len(ok) else int(np.argmax(rec[:-1]))
    Th, P, R = float(thr[ti]), float(prec[ti]), float(rec[ti])
    tn, fp, fn, tp = confusion_matrix(yte, (p >= Th)).ravel()
    metrics = dict(pr_auc=round(average_precision_score(yte, p), 4),
                   threshold=round(Th, 4), precision_at_target=round(P, 4),
                   recall_at_target=round(R, 4),
                   false_positive_rate=round(fp / max(fp + tn, 1), 4),
                   tp=int(tp), fp=int(fp), fn=int(fn), tn=int(tn))
    os.makedirs(f'{a.out}/models', exist_ok=True)
    os.makedirs(f'{a.out}/results', exist_ok=True)
    torch.save({'state_dict': model.state_dict(), 'threshold': Th, 'n_time': T}, f'{a.out}/models/cnn.pt')
    json.dump(metrics, open(f'{a.out}/results/cnn_metrics.json', 'w'), indent=2)
    print('METRICS', json.dumps(metrics))


if __name__ == '__main__':
    main()
